
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
            compose_repo="http://download.eng.pek2.redhat.com/rhel-8/nightly/RHEL-8"
        else
            compose_repo="http://download.eng.pek2.redhat.com/rhel-8/rel-eng/RHEL-8"
        fi
        wget -O compose_status $compose_repo/${compose_id}/STATUS
        WGET_STATUS=$?
        compose_status=`cat compose_status`
        until [ "$WGET_STATUS" == "0" -a "$compose_status" == "FINISHED" ]
        do
            echo "The new compose ${compose_id} is not ready in pek2 repo now. Waiting..."
            sleep 10m
            wget -O compose_status $compose_repo/${compose_id}/STATUS
            WGET_STATUS=$?
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
    export TARGET="libguestfs-rhel8"
    $WORKSPACE/xen-ci/utils/libguestfs_provision_env.sh provision_beaker
    '''
}

def runtest() {
    sh '''
    #!/bin/bash -x
    echo "runtest"
    '''
}

// Global variables
COMPOSE_URL=""
COMPOSE_ENV_YAML=""
COMPOSE_ID=""
REPO_URL=""
TESTRESULT=""
HTMLURL=""
PKGNAME=""
TMPFILE="" // For saving variables from AWS-*-runtest-pipeline
BUILD_URL=""


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
            customWorkspace "workspace/${env.JOB_NAME}-${env.BUILD_ID}"
        }
    }
    parameters {
        string(defaultValue: '', description: 'Can be triggerd by COMPOSEID_URL or CI_MESSAGE', name: 'CI_MESSAGE')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }
    environment {
        PYTHONPATH="${env.WORKSPACE}"
    }
    stages {
        stage("Download xen-ci") {
            steps {
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'origin/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'xen-ci']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://code.engineering.redhat.com/gerrit/xen-ci']]]
            }
        }
        stage('Parse CI Message') {
            steps {
                script {
                    parse_ci_message()
                    //def ci_env = readYaml file: "job_env.yaml"
                    //currentBuild.displayName = "${env.BUILD_ID}_${ci_env.COMPOSE_ID}"
                    //COMPOSE_ENV_YAML = readFile file: "job_env.yaml".trim()
                    //COMPOSE_URL = ci_env.COMPOSE_URL
                    //PKGNAME = ci_env.PKGNAME
                    //COMPOSE_ID = ci_env.COMPOSE_ID
                    //REPO_URL = ci_env.REPO_URL
                    //BUILD_URL = ci_env.BUILD_URL
                    //echo "${COMPOSE_ENV_YAML}"
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
}
