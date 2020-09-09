def get_test_arch() {
    TEST_ARCH = sh(script: "echo '${env.JOB_NAME}' | awk -F'-' '{print \$5}'", returnStdout: true).trim()
    if (${TEST_ARCH} == "aarch64") {
        UUID = 'a13bd272-f245-11ea-81fd-40a8f01f7098'
    }
    if (${TEST_ARCH} == "ppc64le") {
        UUID = 'b6ab0e5c-f245-11ea-9818-40a8f01f7098'
    }
    if (${TEST_ARCH} == "s390x") {
        UUID = 'da40b3d0-f245-11ea-8b1f-40a8f01f7098'
    }
}

def parse_ci_message() {
    sh '''
    #!/bin/bash -x
    echo ${CI_MESSAGE} | tee $WORKSPACE/CI_MESSAGE.json
    cp -f /home/jenkins-platform/workspace/yoguo/ci_message_module_parse.py $WORKSPACE/xen-ci/utils/
    python $WORKSPACE/xen-ci/utils/ci_message_module_parse.py
    source $WORKSPACE/CI_MESSAGE_ENV.txt

    release_stream=$(cat $WORKSPACE/CI_MESSAGE_ENV.txt | grep -i RELEASE_STREAM | awk -F'=' '{print \$2}')
    branch=${release_stream#*el}
    
    if [[ "$branch" =~ "8.0" ]];then
        tree_name=latest-RHEL-8.0.0
    elif [[ "$branch" =~ "8.1" ]];then
        tree_name=latest-RHEL-8.1.0
    elif [[ "$branch" =~ "8.2" ]];then
        tree_name=latest-RHEL-8.2.0
    elif [[ "$branch" =~ "8.3" ]];then
        tree_name=latest-RHEL-8.3.0
    else
        tree_name=latest-RHEL-8.3.0
    fi

    wget ${COMPOSE_REPO}/$tree_name/COMPOSE_ID || exit 1
    compose_id=$(cat $WORKSPACE/COMPOSE_ID)

    wget ${COMPOSE_REPO}/$tree_name/STATUS
    if [ "`cat STATUS`" != "FINISHED" ]; then
        echo "ERROR: STATUS is not FINISHED"
        compose_id=$(bkr distros-list --limit=500 | grep -i ${tree_name#*latest-} | awk '{print \$2}' | head -n 1)
    fi
    echo "COMPOSE_ID=$compose_id" > $WORKSPACE/COMPOSE_ID.txt
    '''
}

def provision_env() {
    sh '''
    #!/bin/bash -x
    source $WORKSPACE/COMPOSE_ID.txt
    export DISTRO=$COMPOSE_ID
    export TARGET="libguestfs-rhel8-gating"
    export ARCH=${TEST_ARCH}
    $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh provision_beaker || exit 1
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
    export COMPOSE_REPO
    export COMPOSE_ID
    export ID
    export STREAM

    nsvc=${KOJI_TAG#*-}
    nsvc=$(echo $nsvc | sed 's/-/:/g')
    CI_NOTIFIER_VARS="$WORKSPACE/CI_NOTIFIER_VARS.txt"
    echo "NSVC=$nsvc" >> $CI_NOTIFIER_VARS

    cp -f /home/jenkins-platform/workspace/yoguo/libguestfs_runtest_rhel8_beaker_gating.sh $WORKSPACE/xen-ci/utils/
    $WORKSPACE/xen-ci/utils/libguestfs_runtest_rhel8_beaker_gating.sh |& tee $WORKSPACE/log.libguestfs_runtest

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
 
    gzip -c xUnit_log.xml > xunit_result.gz
    xunit_result=$(base64 -w0 xunit_result.gz)
    echo "XUNIT_RESULT=$xunit_result" >> $CI_NOTIFIER_VARS

    # Teardown Env
    $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh teardown_beaker
    '''
}

def send_ci_message() {
    ci = readYaml file: 'ci_message_env.yaml'
    String date = sh(script: 'date -uIs', returnStdout: true).trim()
    echo "${date}"
    def test_result = sh(script: "cat $WORKSPACE/CI_NOTIFIER_VARS.txt | grep -i TEST_RESULT | awk -F'=' '{print \$2}'", returnStdout: true).trim()
    def xunit_result = sh(script: "cat $WORKSPACE/CI_NOTIFIER_VARS.txt | grep -i XUNIT_RESULT | awk -F'=' '{print \$2}'", returnStdout: true).trim()
    def nsvc = sh(script: "cat $WORKSPACE/CI_NOTIFIER_VARS.txt | grep -i NSVC | awk -F'=' '{print \$2}'", returnStdout: true).trim()
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
    "type": "redhat-module",
    "id": "${ci.ID}",
    "name": "virt",
    "stream": "${ci.STREAM}",
    "version": "${ci.VERSION}",
    "context": "${ci.CONTEXT}",
    "issuer": "${ci.OWNER}",
    "nsvc": "${nsvc}" 
  },
  "system": {
    "os": "RHEL8",
    "provider": "${provider}",
    "architecture": "${TEST_ARCH}"
  },
  "label": [
    "${TEST_ARCH}"
  ],
  "type": "tier1",
  "category": "functional",
  "thread_id": "${ci.ID}-${TEST_ARCH}-gating",
  "status": "${test_result}",
  "namespace": "xen-ci.libguestfs.redhat-module",
  "generated_at": "${date}",
  "xunit": "${xunit_result}",
  "version": "0.1.0"
}"""
    echo "${message_content}"
    return sendCIMessage (
        providerName: 'Red Hat UMB',
        overrides: [topic: 'VirtualTopic.eng.ci.redhat-module.test.complete'],
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

// Global variables
TEST_ARCH=""
UUID=""

properties(
    [
        parameters(
            [
                string(name: 'CI_MESSAGE', defaultValue: '', description: 'Content of Red Hat UMB Msg')
            ]
        ),
        pipelineTriggers(
            [[$class: 'CIBuildTrigger',
                noSquash: true,
                providerData: [
                    $class: 'ActiveMQSubscriberProviderData',
                    name: 'Red Hat UMB',
                    overrides: [
                        topic: 'Consumer.rh-jenkins-ci-plugin.${UUID}.VirtualTopic.eng.mbs.module.state.change'
                    ],
                    checks: [
                        [
                            expectedValue: 'done',
                            field: '$.state_name'
                        ],
                        [
                            expectedValue: 'virt',
                            field: '$.name'
                        ],
                        [
                            expectedValue: 'false',
                            field: '$.scratch'
                        ]
                    ]
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
        COMPOSE_REPO = 'http://download.eng.bos.redhat.com/rhel-8/rel-eng/RHEL-8'
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
                    get_test_arch()
                    parse_ci_message()
                    def ci_env = readYaml file: "ci_message_env.yaml"
                    echo "${ci_env.KOJI_TAG}"
                    currentBuild.displayName = "#${env.BUILD_ID}_${ci_env.KOJI_TAG}"
                }
            }
        }
        stage ("Provision Env") {
            steps {
                script {
                    echo "Provisioning ..."
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
                thresholds: [[$class: 'FailedThreshold', failureThreshold: '1']],
                tools: [[$class: 'JUnitType', pattern: 'xUnit.xml']]])
            send_notify()
        }
    }

}
