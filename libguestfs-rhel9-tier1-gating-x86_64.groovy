
def parse_ci_message() {
    sh '''
    #!/bin/bash -x
    echo ${CI_MESSAGE} | tee $WORKSPACE/CI_MESSAGE.json
    python $WORKSPACE/xen-ci/utils/ci_message_parse.py
    source $WORKSPACE/CI_MESSAGE_ENV.txt
    
    tag=$(grep -i tag $WORKSPACE/CI_MESSAGE_ENV.txt | awk -F'=' '{print \$2}')
    label=$(echo $tag | awk -F'-' '{print \$3}')
    if [[ "$tag" =~ "9.0.0" ]]; then
        tree_name=latest-RHEL-9.0.0
        compose_id=$($WORKSPACE/xen-ci/utils/libguestfs_get_compose_id.sh RHEL-9.0.0)
        compose_repo=$NIGHTLY_REPO-${label^}
        distro_id=$(bkr distros-list --name=RHEL-9% --tag=CTS_NIGHTLY --tag=${label^}-0.1 --limit=1 | grep RHEL-9.0.0 | awk '{print \$2}')
    fi

    echo "COMPOSE_ID=$compose_id" > $WORKSPACE/COMPOSE_ID.txt
    echo "DISTRO_ID=$distro_id" >> $WORKSPACE/COMPOSE_ID.txt
    echo "COMPOSE_REPO=$compose_repo" > $WORKSPACE/COMPOSE_REPO.txt 
    echo "TREE_NAME=$tree_name" >> $WORKSPACE/COMPOSE_REPO.txt
    '''
}

def provision_env() {
    sh '''
    #!/bin/bash -x
    source $WORKSPACE/COMPOSE_ID.txt
    export DISTRO=$COMPOSE_ID
    export TARGET="libguestfs-rhel9-os"
    resource_location="openstack"
    $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh provision_openstack || { 
        export TARGET="libguestfs-rhel9-gating"
        resource_location="beaker"
        export DISTRO=$DISTRO_ID
        $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh provision_beaker || exit 1
    }

    echo "RESOURCE_LOCATION=$resource_location" >> $WORKSPACE/RESOURCES.txt
    echo "TARGET=$TARGET" >> $WORKSPACE/RESOURCES.txt
    '''
}

def runtest() {
    sh '''
    #!/bin/bash -x
    source $WORKSPACE/CI_MESSAGE_ENV.txt
    source $WORKSPACE/RESOURCES.txt
    source $WORKSPACE/COMPOSE_REPO.txt
    source $WORKSPACE/COMPOSE_ID.txt

    export SSH_KEYFILE="$WORKSPACE/xen-ci/config/keys/xen-jenkins"
    chmod 600 ${SSH_KEYFILE}
    env

    export GIT_BRANCH="latest-rhel9"
    export EXISTING_NODES
    export RUN_ID
    export TARGET
    export NVR
    export COMPOSE_REPO
    export COMPOSE_ID
    export TREE_NAME
    export ID=$BREW_BUILDID

    if [ "$RESOURCE_LOCATION" == "openstack" ]; then
        cp -f /home/jenkins-platform/workspace/yoguo/libguestfs_runtest_rhel9_os_gating.sh $WORKSPACE/xen-ci/utils/
        $WORKSPACE/xen-ci/utils/libguestfs_runtest_rhel9_os_gating.sh |& tee $WORKSPACE/log.libguestfs_runtest
    else
        cp -f /home/jenkins-platform/workspace/yoguo/libguestfs_runtest_rhel9_beaker_gating.sh $WORKSPACE/xen-ci/utils/
        $WORKSPACE/xen-ci/utils/libguestfs_runtest_rhel9_beaker_gating.sh |& tee $WORKSPACE/log.libguestfs_runtest
    fi

    CI_NOTIFIER_VARS="$WORKSPACE/CI_NOTIFIER_VARS.txt"
    if [ ! -f "xUnit.xml" ];then
        echo "TEST_RESULT=failed" >> $CI_NOTIFIER_VARS
        exit 1
    fi

    $WORKSPACE/xen-ci/utils/mergexml.py xUnit.xml
    FAILURES_NUM=$(xmllint --xpath "//testsuites/testsuite/@failures" xUnit.xml | awk -F'=' '{print \$2}')
    ERRORS_NUM=$(xmllint --xpath "//testsuites/testsuite/@errors" xUnit.xml | awk -F'=' '{print \$2}')

    if [ "$FAILURES_NUM" != '"0"' ] || [ "$ERRORS_NUM" != '"0"' ]; then
        test_result="failed"
    else
        test_result="passed"
    fi
    echo "TEST_RESULT=$test_result" >> $CI_NOTIFIER_VARS

    # Teardown Env
    $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh teardown_${RESOURCE_LOCATION}
    '''
}

def send_ci_message() {
    ci = readYaml file: 'ci_message_env.yaml'
    String date = sh(script: 'date -uIs', returnStdout: true).trim()
    def test_result = sh(script: "cat $WORKSPACE/CI_NOTIFIER_VARS.txt | grep -i TEST_RESULT | awk -F'=' '{print \$2}'", returnStdout: true).trim()
    def provider = sh(script: "cat $WORKSPACE/RESOURCES.txt | grep -i RESOURCE_LOCATION | awk -F'=' '{print \$2}'", returnStdout: true).trim()
  
    def message_content = """\
{
  "ci": {
    "name": "Xen CI",
    "team": "Xen QE",
    "irc": "#AzureQE",
    "url": "https://cloud-jenkins-csb-virt-qe.apps.ocp4.prod.psi.redhat.com",
    "email": "xen-qe-list@redhat.com"
  },
  "run": {
    "url": "${env.BUILD_URL}", 
    "log": "${env.BUILD_URL}console",
    "rebuild": "${env.BUILD_URL}rebuild/parameterized"
 },
  "artifact": {
    "type": "brew-build",
    "id": "${ci.BREW_TASKID}",
    "issuer": "${ci.OWNER}",
    "component": "${ci.PKGNAME}",
    "nvr": "${ci.NVR}",
    "scratch": false
  },
  "system": {
    "os": "RHEL9",
    "provider": "${provider}",
    "architecture": "${TEST_ARCH}"
  },
  "type": "tier1",
  "category": "functional",
  "thread_id": "${ci.BREW_TASKID}-${TEST_ARCH}-gating",
  "status": "${test_result}",
  "namespace": "xen-ci.brew-build",
  "generated_at": "${date}",
  "version": "0.1.0"
}"""
    echo "${message_content}"
    return sendCIMessage (
        providerName: 'Red Hat UMB',
        overrides: [topic: 'VirtualTopic.eng.ci.brew-build.test.complete'],
        messageContent: message_content,
        messageType: 'Custom',
        failOnError: true
    ) 
}

def send_notify() {
    emailext (
    body: """
JOB_NAME: ${env.JOB_NAME}
BUILD_DISPLAY_NAME: ${env.BUILD_DISPLAY_NAME}
RESULT: ${currentBuild.currentResult}
BUILD_URL: ${env.BUILD_URL}
    """,
    subject: "${env.JOB_NAME} - ${env.BUILD_DISPLAY_NAME} - ${currentBuild.currentResult}",
    from: "nobody@nowhere",
    to: "yoguo@redhat.com"
  )
}

properties(
    [
        parameters(
            [
                string(name: 'CI_MESSAGE', defaultValue: '', description: 'Content of Red Hat UMB Msg')
            ]
        ),
        pipelineTriggers(
            [[$class: 'CIBuildTrigger',
                noSquash: false,
                providerData: [
                    $class: 'ActiveMQSubscriberProviderData',
                    name: 'Red Hat UMB',
                    overrides: [
                        topic: 'Consumer.rh-jenkins-ci-plugin.5afcb246-a416-46cb-94e7-43dc3cb1735b.VirtualTopic.eng.brew.build.tag.>'
                    ],
                    selector:  name = \'libguestfs\' AND (tag LIKE \'rhel-9._._-alpha-gate\' OR tag LIKE \'rhel-9._._-beta-gate\' OR tag LIKE \'rhel-9._._-gate\' OR tag LIKE \'rhel-9._._-z-gate\')',
                    timeout: 30
                ]
            ]]
        )
    ]
)

pipeline {
    agent {
        node {
            label "jslave-libguestfs"
            customWorkspace "workspace/${env.JOB_NAME}"
        }
    }
    parameters {
        string(defaultValue: '', description: 'Can be triggerd by CI_MESSAGE', name: 'CI_MESSAGE')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '180', numToKeepStr: '60'))
        timestamps()
        timeout(time: 3, unit: 'DAYS')
    }
    environment {
        MILESTONE_COMPOSE_REPO = 'http://download.eng.bos.redhat.com/rhel-9/rel-eng/RHEL-9'
        COMPOSES_REPO = 'http://download.eng.bos.redhat.com/rhel-9/composes/RHEL-9'
        NIGHTLY_REPO = 'http://download.eng.bos.redhat.com/rhel-9/nightly/RHEL-9'
        TEST_ARCH = 'x86_64'
    }
    stages {
        stage("Checkout xen-ci") {
            steps {
                cleanWs()
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'origin/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'xen-ci']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://code.engineering.redhat.com/gerrit/xen-ci']]]
            }
        }
        stage('Parse CI Message') {
            steps {
                script {
                    parse_ci_message()
                    def compose_id = sh(script: "cat $WORKSPACE/CI_MESSAGE_ENV.txt | grep -i compose_id | awk -F'=' '{print \$2}'", returnStdout: true).trim()
                    currentBuild.displayName = "#${env.BUILD_ID}_${compose_id}"
                }
            }
        }
        stage ("Provision Env") {
            steps {
                script {
                    //echo "debuging..."
                    provision_env()
                }
            }
        }
        stage ("Run Test") {
            steps {
                script {
                    //echo "debuging..."
                    runtest()
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: '*.txt, *.json, *.xml', fingerprint: true, allowEmptyArchive: true
            step([$class: 'XUnitPublisher',
                thresholds: [[$class: 'FailedThreshold', failureThreshold: '0']],
                tools: [[$class: 'JUnitType', pattern: 'xUnit.xml']]])
            send_notify()
        }
    }

}
