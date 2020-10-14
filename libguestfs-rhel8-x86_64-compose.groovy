
def parse_ci_message() {
    sh '''
    #!/bin/bash -x
    cp -f /home/jenkins-platform/workspace/yoguo/umb_pungi_parse.py $WORKSPACE/xen-ci/utils/
    python $WORKSPACE/xen-ci/utils/umb_pungi_parse.py
    source $WORKSPACE/CI_MESSAGE_ENV.txt
    
    if [ "${release_short}" == "RHEL" ] && [ "${release_type}" == "ga" ]; then
        echo "milestone or nightly compose"
        distro=${compose_id}

        # Check if the compose status if finished
        if [ "${compose_type}" == "nightly" ]; then
            compose_repo="http://download-node-02.eng.bos.redhat.com/rhel-8/nightly/RHEL-8"
        else
            compose_repo="http://download-node-02.eng.bos.redhat.com/rhel-8/rel-eng/RHEL-8"
        fi
        WGET_STATUS=0
        wget -O compose_status $compose_repo/${compose_id}/STATUS || WGET_STATUS=1
        compose_status=`cat compose_status`
        until [ "$WGET_STATUS" == "0" -a "$compose_status" == "FINISHED" ]
        do
            echo "The new compose ${compose_id} is not ready in bos repo now. Waiting..."
            sleep 10m
            WGET_STATUS=0
            wget -O compose_status $compose_repo/${compose_id}/STATUS || WGET_STATUS=1
            compose_status=`cat compose_status`
        done
    else
        # RHEL-AV (or nighlty) or updates compose
        if [ "${compose_type}" == "nightly" ]; then
            compose_repo="http://download-node-02.eng.bos.redhat.com/rhel-8/nightly/RHEL-8"
        else
            compose_repo="http://download-node-02.eng.bos.redhat.com/rhel-8/rel-eng/RHEL-8"
        fi
        tree_name="latest-RHEL-${release_version%.*}.0"
        wget $compose_repo/$tree_name/COMPOSE_ID -O distro
        distro=`cat distro`
    fi

    echo "DISTRO=$distro" > $WORKSPACE/DISTRO.txt

    if [ "${release_short}" == "RHEL" ]; then
        echo "milestone or updates or nightly compose"
        variant="AppStream"
    elif [ "${release_short}" == "ADVANCED-VIRT" ]; then
        echo "rhel-av compose"
        variant="Advanced-virt"
    fi

    wget ${location}/$variant/x86_64/os/Packages/ -O packages.html
    nvr=$(cat packages.html | grep -v python | egrep -oe "libguestfs-[0-9]{1}.[0-9]{2}.[0-9]{1,3}-[.0-9]{2,6}module\\+el8.[0-9]{1}.[0-9]{1}\\+[0-9]{,6}\\+[0-9,a-z]{,10}" | head -n 1)
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


//https://plugins.jenkins.io/jms-messaging
properties([
  pipelineTriggers([
    [
      $class: 'CIBuildTrigger',
      noSquash: false,
      providerData: [
        $class: 'ActiveMQSubscriberProviderData',
        name: 'Red Hat UMB',
        overrides: [
          topic: 'Consumer.rh-jenkins-ci-plugin.1d359474-dc45-11ea-8e0c-40a8f01f7098.VirtualTopic.eng.pungi.status-change.>'
        ],
        selector: 'release_version LIKE \'8._._\' AND status = \'FINISHED\' AND (release_short = \'RHEL\' OR release_short = \'ADVANCED-VIRT\') AND compose_type = \'production\'',
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
        PYTHONPATH="${env.WORKSPACE}"
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
                    provision_env()
                }
            }
        }
        stage ("Run Test") {
            steps {
                script {
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
