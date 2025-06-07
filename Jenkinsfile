pipeline {
    agent any

    parameters {
        string(name: 'BRANCH', defaultValue: 'main', description: 'Branch to build')
        string(name: 'SONAR_PROJECT_KEY', defaultValue: 'War-App', description: 'SonarQube Project Key')
        string(name: 'SONAR_PROJECT_NAME', defaultValue: 'Backend-War-App', description: 'SonarQube Project Name')
        string(name: 'REPO_URL', defaultValue: 'https://github.com/Faikhan147/Real-Time-Backend-App-WAR-Repo.git', description: 'Git repo URL')
        choice(name: 'ENVIRONMENT', choices: ['qa', 'staging', 'prod'], description: 'Select the environment to deploy')
    }

    environment {
        DOCKER_IMAGE = "faisalkhan35/my-war-app"
        SLACK_WEBHOOK_URL = credentials('slack-webhook')
        TAG = "${BUILD_NUMBER}"
        SONAR_PROJECT_KEY = "${params.SONAR_PROJECT_KEY}"
        SONAR_PROJECT_NAME = "${params.SONAR_PROJECT_NAME}"
        SONAR_SCANNER_HOME = "/opt/sonar-scanner"
        IMAGE_NAME_TAG = "${DOCKER_IMAGE}:${TAG}"
        HELM_CHART_DIR = "helm/war-app-chart"
        WAR_APP_URL = credentials('backend-war-app-url')
        KUBECONFIG = '/var/lib/jenkins/.kube/config'  

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
    steps {
        script {
            // Checking if any .war file exists in the target folder
            def warFiles = sh(script: 'ls -l WAR-Project/target/*.war', returnStdout: true).trim()

            // If .war files are found, archive them
            if (warFiles) {
                archiveArtifacts artifacts: 'WAR-Project/target/*.war', fingerprint: true
            } else {
                echo 'No WAR files found, skipping artifact archiving.'
            }
        }
    }
}

stage('Create SonarQube Project') {
    steps {
        withCredentials([string(credentialsId: 'Sonar-Global-Token', variable: 'SONAR_TOKEN')]) {
            sh """
                curl -u ${SONAR_TOKEN}: -X POST "http://52.66.244.142:9000/api/projects/create?name=${SONAR_PROJECT_NAME}&project=${SONAR_PROJECT_KEY}"
            """
        }
    }
}


        stage('SonarQube Code Analysis') {
            steps {
                withSonarQubeEnv('Sonar-Authentication') {
                    dir('WAR-Project') {
                        script {
                            echo "Starting SonarQube scan..."
                            sh """
                                ${SONAR_SCANNER_HOME}/bin/sonar-scanner \
                                -Dsonar.projectKey=${SONAR_PROJECT_KEY} \
                                -Dsonar.projectName=${SONAR_PROJECT_NAME} \
                                -Dsonar.sources=. \
                                -Dsonar.java.binaries=target/classes \
                                -Dsonar.host.url=http://52.66.244.142:9000
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
            def clusterMap = [
                'qa'     : 'qa-eks-cluster',
                'staging': 'staging-eks-cluster',
                'prod'   : 'prod-eks-cluster'
            ]
            def selectedCluster = clusterMap[params.ENVIRONMENT]

            withAWS(credentials: 'aws-credentials', region: 'ap-south-1') {
                echo "Updating kubeconfig for cluster: ${selectedCluster}"
                sh """
                    aws eks update-kubeconfig --region ap-south-1 --name ${selectedCluster} \
                    || { echo "Failed to update kubeconfig for ${selectedCluster}"; exit 1; }
                """
            }
        }
    }
}

stage('Deploy to QA/Staging with Helm') {
    when {
        expression { return params.ENVIRONMENT == 'qa' || params.ENVIRONMENT == 'staging' }
    }
    steps {
        script {
            def chartValues = "image.repository=${DOCKER_IMAGE},image.tag=${BUILD_NUMBER},environment=${params.ENVIRONMENT}"

            withAWS(credentials: 'aws-credentials', region: 'ap-south-1') {
                sh """
                    kubectl get namespace ${params.ENVIRONMENT} || kubectl create namespace ${params.ENVIRONMENT}
                """
                retry(3) {
                    echo "Deploying to ${params.ENVIRONMENT} environment..."
                    sh """
                        helm upgrade --install war-app-${params.ENVIRONMENT} ${HELM_CHART_DIR} \\
                        --namespace ${params.ENVIRONMENT} \\
                        --set ${chartValues} \\
                        --set resources.requests.memory=256Mi \\
                        --set resources.requests.cpu=200m \\
                        --set resources.limits.memory=512Mi \\
                        --set resources.limits.cpu=500m || { echo 'Helm deployment failed!'; exit 1; }
                    """
                }
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

            def revisionCount = sh(script: "helm history war-app-${params.ENVIRONMENT} --namespace ${params.ENVIRONMENT} | wc -l", returnStdout: true).trim().toInteger()

            // header + at least 2 revisions = 3 lines
            if (revisionCount >= 3) {
                def lastRevision = sh(script: "helm history war-app-${params.ENVIRONMENT} --namespace ${params.ENVIRONMENT} | tail -2 | head -1 | awk '{print \$1}'", returnStdout: true).trim()
                echo "Rolling back to revision ${lastRevision}"
                sh "helm rollback war-app-${params.ENVIRONMENT} ${lastRevision} --namespace ${params.ENVIRONMENT}"
            } else {
                echo "Not enough revisions to perform rollback. Skipping."
            }
        }
    }
}


// Monitoring Deployment for QA/Staging
stage('Monitor Deployment for QA/Staging (Pods + War App Health Check)') {
    when {
        expression { return params.ENVIRONMENT != 'prod' } // Only for QA/Staging
    }
    steps {
        script {
            echo "🧪 Monitoring deployment status for ${params.ENVIRONMENT}..."

            retry(3) {
                withEnv(["ENVIRONMENT=${params.ENVIRONMENT}"]) {
                    sh '''#!/bin/bash
                    echo "⏳ Waiting for all pods to be in 'Running' status in namespace $ENVIRONMENT..."
                    MAX_RETRIES=12
                    RETRY_COUNT=0

                    while true; do
                        POD_PHASES=$(kubectl get pods -n "$ENVIRONMENT" -o jsonpath='{.items[*].status.phase}')
                        NOT_RUNNING=$(echo "$POD_PHASES" | grep -v "Running" || true)

                        if [ -z "$NOT_RUNNING" ]; then
                            echo "✅ All pods are running in $ENVIRONMENT."
                            break
                        fi

                        RETRY_COUNT=$((RETRY_COUNT+1))
                        if [ "$RETRY_COUNT" -ge "$MAX_RETRIES" ]; then
                            echo "❌ Timeout: Some pods are not in Running state."
                            kubectl get pods -n "$ENVIRONMENT"
                            exit 1
                        fi

                        echo "⏳ Pods not ready yet. Retrying in 5s... [$RETRY_COUNT/$MAX_RETRIES]"
                        sleep 5
                    done
                    '''
                }
            }

            retry(3) {
                withEnv(["WAR_APP_URL=$WAR_APP_URL"]) {
                    sh '''#!/bin/bash
                    echo "🌐 Performing War App health check on $WAR_APP_URL ..."
                    sleep 10  # wait for service to be ready
                    echo "🔍 DNS resolution test:"
                    nslookup "$WAR_APP_URL" || echo "❗ DNS resolution failed."

                    echo "🔍 CURL with verbose output:"
                    curl -v "$WAR_APP_URL" || echo "❗ CURL failed."

                    STATUS_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$WAR_APP_URL")
                    echo "ℹ️ HTTP Status Code: $STATUS_CODE"

                    if [ "$STATUS_CODE" -ne 200 ]; then
                        echo "❌ War App health check failed with status code $STATUS_CODE"
                        exit 1
                    fi

                    echo "✅ App is healthy. HTTP $STATUS_CODE"
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
            def chartValues = "image.repository=${DOCKER_IMAGE},image.tag=${BUILD_NUMBER},environment=${params.ENVIRONMENT}"

            withAWS(credentials: 'aws-credentials', region: 'ap-south-1') {
                sh """
                    kubectl get namespace ${params.ENVIRONMENT} || kubectl create namespace ${params.ENVIRONMENT}
                """
                retry(3) {
                    echo "Deploying to ${params.ENVIRONMENT} environment..."
                    sh """
                        helm upgrade --install war-app-${params.ENVIRONMENT} ${HELM_CHART_DIR} \\
                        --namespace ${params.ENVIRONMENT} \\
                        --set ${chartValues} \\
                        --set resources.requests.memory=512Mi \\
                        --set resources.requests.cpu=300m \\
                        --set resources.limits.memory=1024Mi \\
                        --set resources.limits.cpu=1000m
                    """
                }
            }
        }
    }
}

// Monitoring Deployment for Production
stage('Monitor Deployment for Production (Pods + War-App Health Check)') {
    when {
        expression { return params.ENVIRONMENT == 'prod' }
    }
    steps {
        script {
            echo "📡 Monitoring production deployment status for ${params.ENVIRONMENT}..."

            retry(3) {
                withEnv(["ENVIRONMENT=${params.ENVIRONMENT}"]) {
                    sh '''#!/bin/bash
                    echo "⏳ Waiting for all pods to be in 'Running' status in namespace $ENVIRONMENT..."
                    MAX_RETRIES=12
                    RETRY_COUNT=0

                    while true; do
                        POD_PHASES=$(kubectl get pods -n "$ENVIRONMENT" -o jsonpath='{.items[*].status.phase}')
                        NOT_RUNNING=$(echo "$POD_PHASES" | grep -v "Running" || true)

                        if [ -z "$NOT_RUNNING" ]; then
                            echo "✅ All pods are running in $ENVIRONMENT."
                            break
                        fi

                        RETRY_COUNT=$((RETRY_COUNT+1))
                        if [ "$RETRY_COUNT" -ge "$MAX_RETRIES" ]; then
                            echo "❌ Timeout: Some pods are not in Running state."
                            kubectl get pods -n "$ENVIRONMENT"
                            exit 1
                        fi

                        echo "⏳ Pods not ready yet. Retrying in 5s... [$RETRY_COUNT/$MAX_RETRIES]"
                        sleep 5
                    done
                    '''
                }
            }

            retry(3) {
                withEnv(["WAR_APP_URL=$WAR_APP_URL"]) {
                    sh '''#!/bin/bash
                    echo "🌐 Performing War App health check on $WAR_APP_URL ..."
                    sleep 10  # wait for service to be ready
                    echo "🔍 DNS resolution test:"
                    nslookup "$WAR_APP_URL" || echo "❗ DNS resolution failed."

                    echo "🔍 CURL with verbose output:"
                    curl -v "$WAR_APP_URL" || echo "❗ CURL failed."

                    STATUS_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$WAR_APP_URL")
                    echo "ℹ️ HTTP Status Code: $STATUS_CODE"

                    if [ "$STATUS_CODE" -ne 200 ]; then
                        echo "❌ War App health check failed with status code $STATUS_CODE"
                        exit 1
                    fi

                    echo "✅ App is healthy. HTTP $STATUS_CODE"
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
