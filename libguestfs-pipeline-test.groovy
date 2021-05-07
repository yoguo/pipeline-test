properties([
  pipelineTriggers([
    [
      $class: 'CIBuildTrigger',
      noSquash: false,
      providerData: [
        $class: 'ActiveMQSubscriberProviderData',
        name: 'Red Hat UMB',
        overrides: [
          topic: 'Consumer.rh-jenkins-ci-plugin.b3e63a30-c416-4945-a29d-b7e1ebafbd41.VirtualTopic.eng.cts.compose-tagged.>'
        ],
        checks: [
          [
            expectedValue: '^nightly$',
            field: '$.tag'
          ],
          [
            expectedValue: '^RHEL$',
            field: '$.compose.compose_info.payload.release.short'
          ],
          [
            expectedValue: '9.*',
            field: '$.compose.compose_info.payload.release.version'
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
        COMPOSES_REPO = 'http://download.eng.bos.redhat.com/rhel-9/composes/RHEL-9'
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
                    echo "debuging..."
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
            //send_notify()
        }
    }

}
