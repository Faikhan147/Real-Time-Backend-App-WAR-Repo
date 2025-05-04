pipeline {
    agent any

    parameters {
        string(name: 'BRANCH', defaultValue: 'main', description: 'Branch to build')
        string(name: 'REPO_URL', defaultValue: 'https://github.com/Faikhan147/Real-Time-Backend-App-WAR-Repo.git', description: 'Git repo URL')
        choice(name: 'ENVIRONMENT', choices: ['qa', 'staging', 'prod'], description: 'Select the environment to deploy')
    }

    environment {
        DOCKER_IMAGE = "faisalkhan35/my-war-app"
        SLACK_WEBHOOK_URL = credentials('slack-webhook')
        BACKEND_IMAGE_NAME = "faisalkhan35/my-war-app"
        TAG = "${BUILD_NUMBER}"
        SONAR_PROJECT_KEY = "War-App"
        SONAR_PROJECT_NAME = "Backend-War-App"
        SONAR_SCANNER_HOME = "/opt/sonar-scanner"
        IMAGE_NAME_TAG = "${BACKEND_IMAGE_NAME}:${TAG}"
        HELM_CHART_DIR = "helm/war-app-chart"
        WAR-APP_URL = credentials('war-app-url')
    }

    stages {
        stage('Checkout Code') {
            steps {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${params.BRANCH}"]],
                    userRemoteConfigs: [[
                        url: "${params.REPO_URL}",
                        credentialsId: 'github-credentials'
                    ]]
                ])
            }
        }


        // Build Maven Project using Maven Wrapper
        stage('Build Maven Project') {
            steps {
                dir('WAR-Project') {
                    sh "chmod +x ./mvnw"  // Giving execute permission
                    sh "./mvnw clean package"  // Using Maven Wrapper
                }
            }
        }

 stage('Artifact Archiving') {
            when {
                expression { return fileExists('WAR-Project/target/*.war') }
            }
            steps {
                dir('WAR-Project') {
                    archiveArtifacts artifacts: 'target/*.war', fingerprint: true
                }
            }
        }

        stage('SonarQube Code Analysis') {
            steps {
                withSonarQubeEnv('Sonar-Global-Token') {
                    dir('WAR-Project') {
                        script {
                            echo "Starting SonarQube scan..."
                            sh """
                                ${SONAR_SCANNER_HOME}/bin/sonar-scanner \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                -Dsonar.projectName=${SONAR_PROJECT_NAME} \
                                -Dsonar.sources=. \
                                -Dsonar.host.url=http://13.233.223.130:9000
                            """
                        }
                    }
                }
            }
        }

        stage('SonarQube Quality Gate Check') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    echo "Waiting for SonarQube Quality Gate..."
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                dir('WAR-Project') {
                    script {
                        def commitHash = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                        echo "Building Docker image with commit hash: ${commitHash}"
                        sh """
                            docker build --cache-from ${DOCKER_IMAGE}:${TAG} \
                            --label commit=${commitHash} \
                            -t ${IMAGE_NAME_TAG} . || { echo 'Docker build failed!'; exit 1; }
                        """
                    }
                }
            }
        }

        stage('Trivy Scan - Critical and High') {
            steps {
                echo "Starting Trivy scan for vulnerabilities..."
                sh """
                    trivy image --exit-code 1 \
                    --severity CRITICAL,HIGH \
                    --format table \
                    --ignore-unfixed \
                    ${IMAGE_NAME_TAG} || { echo 'Trivy scan failed!'; exit 1; }
                """
            }
        }

        stage('Run Unit & Integration Tests') {
            when {
                expression { fileExists('WAR-Project/package.json') }
            }
            steps {
                dir('WAR-Project') {
                    script {
                        echo "Running unit tests..."
                        sh """
                            npm install || { echo 'npm install failed!'; exit 1; }
                            npm run test -- --coverage --reporters=default --reporters=jest-html-reporter || { echo 'Unit tests failed!'; exit 1; }
                        """
                        publishHTML(target: [
                            reportDir: 'WAR-Project',
                            reportFiles: 'jest-html-report.html',
                            reportName: 'Jest Test Report'
                        ])
                    }
                }
            }
        }

        stage('DockerHub Login') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-credentials', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    echo "Logging in to DockerHub..."
                    sh "echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin || { echo 'DockerHub login failed!'; exit 1; }"
                }
            }
        }

        stage('Push Docker Image with Retry') {
            steps {
                retry(3) {
                    echo "Pushing Docker image to DockerHub..."
                    sh "docker push ${IMAGE_NAME_TAG} || { echo 'Docker push failed!'; exit 1; }"
                }
            }
        }

        stage('Helm Lint and Test') {
            steps {
                script {
                    echo "Linting and testing Helm chart..."
                    sh """
                        helm lint ${HELM_CHART_DIR} || { echo 'Helm lint failed!'; exit 1; }
                        helm template war-app-${params.ENVIRONMENT} ${HELM_CHART_DIR} || { echo 'Helm template failed!'; exit 1; }
                    """
                }
            }
        }

        stage('AWS EKS Update Kubeconfig') {
            steps {
                script {
                    echo "Updating kubeconfig for EKS..."
                    sh 'aws eks update-kubeconfig --region ap-south-1 --name Faisal || { echo "Failed to update kubeconfig!"; exit 1; }'
                }
            }
        }

        stage('Deploy to QA/Staging with Helm') {
            when {
                expression { return params.ENVIRONMENT == 'qa' || params.ENVIRONMENT == 'staging' }
            }
            steps {
                script {
                    sh """
                        kubectl get namespace ${params.ENVIRONMENT} || kubectl create namespace ${params.ENVIRONMENT}
                    """
                    def chartValues = "image.repository=${DOCKER_IMAGE},image.tag=${BUILD_NUMBER},environment=${params.ENVIRONMENT}"
                    retry(3) {
                        echo "Deploying to ${params.ENVIRONMENT} environment..."
                        sh """
                            helm upgrade --install war-app-${params.ENVIRONMENT} ${HELM_CHART_DIR} \
                            --namespace ${params.ENVIRONMENT} \
                            --set ${chartValues} \
                            --set resources.requests.memory=128Mi \
                            --set resources.requests.cpu=100m \
                            --set resources.limits.memory=256Mi \
                            --set resources.limits.cpu=250m || { echo 'Helm deployment failed!'; exit 1; }
                        """
                    }
                }
            }
        }

        stage('Rollback (if needed)') {
            when {
                expression { return params.ENVIRONMENT == 'qa' || params.ENVIRONMENT == 'staging' }
            }
            steps {
                script {
                    echo "Checking if rollback is needed..."
                    def releaseHistory = sh(script: "helm history war-app-${params.ENVIRONMENT} --namespace ${params.ENVIRONMENT} --output json", returnStdout: true).trim()
                    if (releaseHistory.contains('"revision":')) {
                        def lastRevision = sh(script: "helm history war-app-${params.ENVIRONMENT} --namespace ${params.ENVIRONMENT} | tail -2 | head -1 | awk '{print \$1}'", returnStdout: true).trim()
                        echo "Rolling back to revision ${lastRevision}"
                        sh "helm rollback war-app-${params.ENVIRONMENT} ${lastRevision} --namespace ${params.ENVIRONMENT}"
                    } else {
                        echo "No previous revision found. Skipping rollback."
                    }
                }
            }
        }

// Monitoring Deployment for QA/Staging
stage('Monitor Deployment (Pods + War-App Health Check)') {
    when {
        expression { return params.ENVIRONMENT != 'prod' } // Only for QA/Staging
    }
    steps {
        script {
            echo "Monitoring deployment status..."
            retry(3) {
                withEnv(["ENVIRONMENT=${params.ENVIRONMENT}"]) {
                    sh '''#!/bin/bash
                        kubectl get pods -n "$ENVIRONMENT" || { echo 'Failed to get pods!'; exit 1; }
                    '''
                    sh '''#!/bin/bash
                        POD_STATUS=$(kubectl get pods -n "$ENVIRONMENT" -o jsonpath='{.items[*].status.phase}')
                        if [[ "$POD_STATUS" != *"Running"* ]]; then
                            echo "❌ Not all pods are running."
                            exit 1
                        fi
                    '''
                }
            }
            retry(3) {
                withEnv(["WAR-APP_URL=${WAR-APP_URL}"]) {
                    sh '''#!/bin/bash
                        STATUS_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$WAR-APP_URL")
                        if [ "$STATUS_CODE" -ne 200 ]; then
                            echo "❌ War-App health check failed."
                            exit 1
                        fi
                    '''
                }
            }
        }
    }
}

        stage('Approval for Production') {
            when {
                expression { return params.ENVIRONMENT == 'prod' }
            }
            steps {
                input message: "Deploy to Production?", ok: "Yes, deploy now"
            }
        }

        stage('Deploy to Production with Helm') {
            when {
                expression { return params.ENVIRONMENT == 'prod' }
            }
            steps {
                script {
                    sh """
                        kubectl get namespace prod || kubectl create namespace prod
                    """
                    def chartValues = "image.repository=${DOCKER_IMAGE},image.tag=${BUILD_NUMBER},environment=prod"
                    retry(3) {
                        echo "Deploying to Production..."
                        sh """
                            helm upgrade --install war-app-prod ${HELM_CHART_DIR} \
                            --namespace prod \
                            --set ${chartValues} \
                            --set resources.requests.memory=128Mi \
                            --set resources.requests.cpu=100m \
                            --set resources.limits.memory=256Mi \
                            --set resources.limits.cpu=250m || { echo 'Production deployment failed!'; exit 1; }
                        """
                    }
                }
            }
        }

// Monitoring for Production Deployment
stage('Monitor Deployment for Production (Pods + War-App Health Check)') {
    when {
        expression { return params.ENVIRONMENT == 'prod' }
    }
    steps {
        script {
            echo "Monitoring production deployment status..."
            retry(3) {
                withEnv(["ENVIRONMENT=${params.ENVIRONMENT}"]) {
                    sh '''#!/bin/bash
                        kubectl get pods -n "$ENVIRONMENT" || { echo 'Failed to get pods!'; exit 1; }
                    '''
                    sh '''#!/bin/bash
                        POD_STATUS=$(kubectl get pods -n "$ENVIRONMENT" -o jsonpath='{.items[*].status.phase}')
                        if [[ "$POD_STATUS" != *"Running"* ]]; then
                            echo "❌ Not all pods are running."
                            exit 1
                        fi
                    '''
                }
            }
            retry(3) {
                withEnv(["WAR-APP_URL=${WAR-APP_URL}"]) {
                    sh '''#!/bin/bash
                        STATUS_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$WAR-APP_URL")
                        if [ "$STATUS_CODE" -ne 200 ]; then
                            echo "❌ War-App health check failed."
                            exit 1
                        fi
                    '''
                }
            }
        }
    }
}

        stage('Docker Image Cleanup') {
            steps {
                script {
                    echo "Cleaning up unused Docker images..."
                    sh """
                        docker image prune -f || { echo 'Docker image cleanup failed!'; exit 1; }
                    """
                }
            }
        }

        stage('Slack Notification') {
            steps {
                script {
                    def message = "*Deployment Status:* ✅ Successful\n*Environment:* ${params.ENVIRONMENT}\n*Build:* #${BUILD_NUMBER}"
                    sh """
                        curl -X POST -H 'Content-type: application/json' \
                        --data '{"text": "${message}"}' ${SLACK_WEBHOOK_URL}
                    """
                }
            }
        }
    }

    post {
        success {
            script {
                build job: 'Slack-Notifier', parameters: [
                    string(name: 'STATUS', value: '✅ Deployment successful'),
                    string(name: 'ENV', value: "${params.ENVIRONMENT}")
                ]
            }
        }
        failure {
            script {
                echo "❌ Pipeline failed! Rolling back..."
                build job: 'Slack-Notifier', parameters: [
                    string(name: 'STATUS', value: '❌ Deployment failed - rollback initiated'),
                    string(name: 'ENV', value: "${params.ENVIRONMENT}")
                ]
                
                def lastRevision = sh(script: "helm history war-app-${params.ENVIRONMENT} --namespace ${params.ENVIRONMENT} | tail -2 | head -1 | awk '{print \$1}'", returnStdout: true).trim()
                sh "helm rollback war-app-${params.ENVIRONMENT} ${lastRevision} --namespace ${params.ENVIRONMENT} || echo 'Rollback failed!'"
            }
        }
    }
}
