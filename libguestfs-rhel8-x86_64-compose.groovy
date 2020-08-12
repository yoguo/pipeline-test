
def parse_ci_message() {
    sh '''
    #!/bin/bash -x
    python $WORKSPACE/xen-ci/utils/umb_pungi_parse.py
    cat $WORKSPACE/CI_MESSAGE_ENV.txt
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
        selector: (release_version LIKE \'8._._\') AND status = \'FINISHED\' AND (release_short = \'RHEL\' OR release_short = \'ADVANCED-VIRT\') AND compose_type = \'production'
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
        stage('Parse CI Message') {
            steps {
                cleanWs()
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
        stage("Prepare image") {
            steps {
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'origin/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'xen-ci']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://code.engineering.redhat.com/gerrit/xen-ci']]]
            }
        }
        stage ("Run Test") {
            steps {
                echo "Run test"
            }
        }
    }
    post {
        always {
            archiveArtifacts '*.*'
        }
    }
}
