
def parse_ci_message() {
    sh '''
    #!/bin/bash -x
    echo ${CI_MESSAGE} | tee $WORKSPACE/CI_MESSAGE.json
    cp -f /home/jenkins-platform/workspace/yoguo/ci_message_ctg_parse.py $WORKSPACE/xen-ci/utils/
    python $WORKSPACE/xen-ci/utils/ci_message_ctg_parse.py
    source $WORKSPACE/CI_MESSAGE_ENV.txt
    
    echo "DISTRO=$COMPOSE_ID" > $WORKSPACE/DISTRO.txt
    wget $NIGHTLY_REPO/$COMPOSE_ID/compose/AppStream/$TEST_ARCH/os/Packages/ -O packages.html
    nvr=$(cat packages.html | grep -v python | egrep -oe "libguestfs-[0-9]{1}.[0-9]{2}.[0-9]{1,3}-[.0-9]{2,6}module\\+el9.[0-9]{1}.[0-9]{1}\\+[0-9]{,6}\\+[0-9,a-z]{,10}" | head -n 1)
    echo "NVR=$nvr" >> $WORKSPACE/CI_MESSAGE_ENV.txt
    '''
}

def provision_env() {
    sh '''
    #!/bin/bash -x
    source $WORKSPACE/DISTRO.txt
    export DISTRO
    export TARGET="libguestfs-rhel8"
    $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh provision_beaker
    '''
}

def runtest() {
    sh '''
    #!/bin/bash -x
    source $WORKSPACE/CI_MESSAGE_ENV.txt
    source $WORKSPACE/RESOURCES.txt
    echo "Pinging Test Resources"
    echo $EXISTING_NODES | xargs ping -c 30
    env

    export SSH_KEYFILE="$WORKSPACE/xen-ci/config/keys/xen-jenkins"
    chmod 600 ${SSH_KEYFILE}

    export GIT_BRANCH="latest-rhel8"
    export TEST_ARCH="x86_64"
    export EXISTING_NODES
    export release_version
    export release_type
    export release_short
    export location
    $WORKSPACE/xen-ci/utils/libguestfs_runtest_rhel8.sh |& tee $WORKSPACE/log.libguestfs_runtest
    $WORKSPACE/xen-ci/utils/mergexml.py xUnit.xml

    prefix=$(echo "${NVR} ${compose_id} ${TEST_ARCH}" | sed 's/\\.\\|\\&/_/g' | sed 's/\\+/_/g')
    $WORKSPACE/xen-ci/utils/import_XunitResult2Polarion.py -p RHEL7 -t libguestfs -f xUnit.xml -d $WORKSPACE/xen-ci/database/testcases.db  -r "$prefix" -k zeFae6ceiRiewae
    '''
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


properties([
  pipelineTriggers([
    [
      $class: 'CIBuildTrigger',
      noSquash: false,
      providerData: [
        $class: 'ActiveMQSubscriberProviderData',
        name: 'Red Hat UMB',
        overrides: [
          topic: 'Consumer.rh-jenkins-ci-plugin.60d00e80-27ea-11eb-b738-94e70b707d21.VirtualTopic.eng.cts.compose-tagged'
        ],
        checks: [
          [
            expectedValue: '^nightly$',
            field: '$.tag'
          ]
        ],
        timeout: 30
      ]
    ]
  ])
])

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
        UPDATES_REPO = 'http://download.eng.bos.redhat.com/rhel-9/rel-eng/updates/RHEL-9'
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
                    echo "debuging..."
                    //provision_env()
                }
            }
        }
        stage ("Run Test") {
            steps {
                script {
                    echo "debuging..."
                    //runtest()
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
