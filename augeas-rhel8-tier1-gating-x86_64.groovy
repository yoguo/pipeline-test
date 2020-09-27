
def parse_ci_message() {
    sh '''
    #!/bin/bash -x
    echo ${CI_MESSAGE} | tee $WORKSPACE/CI_MESSAGE.json
    cp -f /home/jenkins-platform/workspace/yoguo/ci_message_parse.py $WORKSPACE/xen-ci/utils/
    python $WORKSPACE/xen-ci/utils/ci_message_parse.py
    source $WORKSPACE/CI_MESSAGE_ENV.txt

    tag=$(grep -i tag $WORKSPACE/CI_MESSAGE_ENV.txt | awk -F'=' '{print \$2}')
    if [[ "$tag" =~ "8.0.0" ]]; then
        tree_name=latest-RHEL-8.0.0
    elif [ "$tag" =~ "8.1.0" ];then
        tree_name=latest-RHEL-8.1.0
    elif [ "$tag" =~ "8.2.0" ];then
        tree_name=latest-RHEL-8.2.0
    elif [ "$tag" =~ "8.3.0" ];then
        tree_name=latest-RHEL-8.3.0
    else
        tree_name=latest-RHEL-8.3.0
    fi

    wget $MILESTONE_COMPOSE_REPO/$tree_name/COMPOSE_ID
    compose_id=$(cat $WORKSPACE/COMPOSE_ID)
    echo "COMPOSE_ID=$compose_id" > $WORKSPACE/COMPOSE_ID.txt
    wget $MILESTONE_COMPOSE_REPO/$tree_name/STATUS || exit 1
    '''
}

def provision_env() {
    sh '''
    #!/bin/bash -x
    source $WORKSPACE/COMPOSE_ID.txt
    export DISTRO=$COMPOSE_ID
    export TARGET="augeas-rhel8-os"
    resource_location="openstack"
    cp -f /home/jenkins-platform/workspace/yoguo/libguestfs_provision_env.sh $WORKSPACE/xen-ci/utils/
    $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh provision_openstack || { 
        export TARGET="libguestfs-rhel8-gating"
        resource_location="beaker"
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
    source $WORKSPACE/COMPOSE_ID.txt

    export SSH_KEYFILE="$WORKSPACE/xen-ci/config/keys/xen-jenkins"
    chmod 600 ${SSH_KEYFILE}
    env

    export GIT_BRANCH="latest-rhel8"
    export EXISTING_NODES
    export RUN_ID
    export COMPOSE_ID
    export TREE_NAME

    if [ "$RESOURCE_LOCATION" == "openstack" ]; then
        cp -f /home/jenkins-platform/workspace/yoguo/augeas_runtest_rhel8_os_gating.sh $WORKSPACE/xen-ci/utils/
        $WORKSPACE/xen-ci/utils/augeas_runtest_rhel8_os_gating.sh |& tee $WORKSPACE/log.libguestfs_runtest
    else
        cp -f /home/jenkins-platform/workspace/yoguo/augeas_runtest_rhel8_beaker_gating.sh $WORKSPACE/xen-ci/utils/
        $WORKSPACE/xen-ci/utils/augeas_runtest_rhel8_beaker_gating.sh |& tee $WORKSPACE/log.libguestfs_runtest
    fi

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
    "url": "https://cloud-jenkins-csb-virt-qe.cloud.paas.psi.redhat.com",
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
    "component": "augeas",
    "nvr": "${ci.NVR}",
    "scratch": false
  },
  "system": {
    "os": "RHEL8",
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
    def compose_id = sh(script: "cat $WORKSPACE/COMPOSE_ID.txt | grep -i COMPOSE_ID | awk -F'=' '{print \$2}'", returnStdout: true).trim()
    emailext (
    body: """
COMPOSE_ID: ${compose_id}
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
                        topic: 'Consumer.rh-jenkins-ci-plugin.22c8cabc-fecf-11ea-a32b-40a8f01f7098.VirtualTopic.eng.brew.>'
                    ],
                    selector: 'type = \'Tag\' AND name = \'augeas\' AND (tag LIKE \'rhel-8._._-gate\' OR tag LIKE \'rhel-8._._-z-gate\')',
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
    options {
        buildDiscarder(logRotator(daysToKeepStr: '180', numToKeepStr: '60'))
        timestamps()
        timeout(time: 1, unit: 'DAYS')
    }
    environment {
        MILESTONE_COMPOSE_REPO = 'http://download.eng.bos.redhat.com/rhel-8/rel-eng/RHEL-8'
        UPDATES_REPO = 'http://download.eng.bos.redhat.com/rhel-8/rel-eng/updates/RHEL-8'
        NIGHTLY_REPO = 'http://download.eng.bos.redhat.com/rhel-8/nightly/RHEL-8'
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
                    def ci_env = readYaml file: "ci_message_env.yaml"
                    currentBuild.displayName = "#${env.BUILD_ID}_${ci_env.NVR}"
                }
            }
        }
        stage ("Provision Env") {
            steps {
                script {
                    provision_env()
                }
            }
        }
        stage ("Run Test") {
            steps {
                script {
                    runtest()
                    send_ci_message()
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
