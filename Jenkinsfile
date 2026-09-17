@Library("slack-library") _

// The following environment variables need to be defined in Jenkins:
// AGENT_HOST               (host of the build agent)
// AGENT_USER               (username of the build agent user)
// CXO_COMPOSER_HOME        (location of cxo-composer project)
//
// docker-agent-one needs Docker Buildx and QEMU/binfmt support for linux/arm64.

pipeline {
    environment {
        NAME = 'Codaxy Conductor'
        REPO = 'https://github.com/codaxy/conductor'
        BRANCH = 'main'
        IMAGE = 'ghcr.io/codaxy/conductor:server'
        PLATFORMS = 'linux/amd64,linux/arm64'
        BUILDX_BUILDER = 'cxo-multiarch'
        DOCKERFILE = './docker/server/Dockerfile'
        JENKINS_DOCKER_NETWORK = 'jenkins-docker_default'
    }

    agent {
        label 'docker-agent-one'
    }

    stages {
        stage('Slack Notify Start') {
            steps {
                script {
                    def notification = startNotification(
                        [
                            projectName: "${NAME}",
                            gitBranch: "${BRANCH}",
                            dockerImage: "${IMAGE}",
                            commits: "${commits}",
                            pipelineId: env.BUILD_ID,
                            pipelineUrl: env.BUILD_URL,
                            triggeredBy: env.BUILD_USER
                        ]
                    )
                    slackSend(blocks: notification)
                }
            }
        }

        stage('Cloning git') {
            steps {
                git branch: "${BRANCH}",
                    credentialsId: 'github-token',
                    url: "${REPO}"
            }
        }

        stage('Build and push docker image') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'github-token',
                        passwordVariable: 'password',
                        usernameVariable: 'username'
                    )
                ]) {
                    sh 'echo ${password} | docker login ghcr.io -u ${username} --password-stdin'

                    sh '''
                        docker buildx inspect ${BUILDX_BUILDER} > /dev/null 2>&1 \
                            || docker buildx create \
                                --name ${BUILDX_BUILDER} \
                                --driver docker-container \
                                --bootstrap

                        docker buildx build \
                            --builder ${BUILDX_BUILDER} \
                            --platform ${PLATFORMS} \
                            --label "org.opencontainers.image.source=${REPO}" \
                            -t ${IMAGE} \
                            -f ${DOCKERFILE} \
                            --push \
                            .
                    '''
                }
            }
        }
    }

    post {
        success {
            script {
                def notification = completedNotification(
                    projectName: "${NAME}",
                    gitBranch: "${BRANCH}",
                    dockerImage: "${IMAGE}",
                    pipelineId: env.BUILD_ID,
                    pipelineUrl: env.BUILD_URL,
                    triggeredBy: env.BUILD_USER
                )
                slackSend(blocks: notification)
            }
        }

        failure {
            script {
                def notification = failureNotification(
                    projectName: "${NAME}",
                    gitBranch: "${BRANCH}",
                    pipelineId: env.BUILD_ID,
                    pipelineUrl: env.BUILD_URL,
                    triggeredBy: env.BUILD_USER
                )
                slackSend(blocks: notification)
            }
        }
    }
}
