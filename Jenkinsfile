pipeline {
    agent any

    environment {
        FRONTEND_IMAGE_NAME = "faisalkhan35/my-war"
        TAG = "latest"
        KUBECONFIG = "/var/lib/jenkins/.kube/config"
        SONAR_PROJECT_KEY = "Application-1"
        SONAR_PROJECT_NAME = "War-Backend-Application"
        SONAR_SCANNER_HOME = "/opt/sonar-scanner"
        IMAGE_NAME_TAG = "${FRONTEND_IMAGE_NAME}:${TAG}"
    }

    stages {
        // Build Maven Project using Maven Wrapper
        stage('Build Maven Project') {
            steps {
                dir('WAR-Project') {
                    sh "chmod +x ./mvnw"  // Giving execute permission
                    sh "./mvnw clean package"  // Using Maven Wrapper
                }
            }
        }

        stage('SonarQube Code Analysis') {
            steps {
                withSonarQubeEnv('Sonar-Global-Token') {
                    dir('WAR-Project') {
                        script {
                            echo "🔍 Running sonar-scanner..."
                            sh "${SONAR_SCANNER_HOME}/bin/sonar-scanner \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                -Dsonar.projectName=${SONAR_PROJECT_NAME} \
                                -Dsonar.sources=. \
                                -Dsonar.java.binaries=target/classes \
                                -Dsonar.host.url=http://13.127.232.147:9000"
                        }
                    }
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                dir('WAR-Project') {
                    sh "docker build -t ${IMAGE_NAME_TAG} ."
                }
            }
        }

        stage('Prod Trivy Scan - Critical Only') {
            steps {
                script {
                    echo "🚨 Scanning Docker image for CRITICAL vulnerabilities before production deployment"
                    sh """
                        trivy image --exit-code 1 \
                        --severity CRITICAL \
                        --format table \
                        --ignore-unfixed \
                        ${IMAGE_NAME_TAG}
                    """
                }
            }
        }

        stage('DockerHub Login') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-credentials', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh "echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin"
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                sh "docker push ${IMAGE_NAME_TAG}"
            }
        }

        stage('Deploy to EKS') {
            steps {
                sh 'kubectl apply -f Deployment.yaml'
                sh 'kubectl apply -f Service.yaml'
            }
        }
    }

    post {
        success {
            echo "✅ Deployment and Analysis Successful!"
        }
        failure {
            echo "❌ Build or Scan Failed. Please Check Logs."
        }
    }
}
