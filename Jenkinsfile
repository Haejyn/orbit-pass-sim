// 신뢰성 시험 파이프라인 — GitHub Actions(.github/workflows/ci.yml)와 같은 build.py 단계를 Jenkins 에서 돌린다.
// 필요 플러그인: workflow-aggregator(Pipeline), git, junit. 에이전트에 JDK 21 과 Python 3 이 PATH 에 있어야 한다.
pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Compile (-Werror)') {
            steps { script { run('python build.py compile') } }
        }
        stage('Unit & property tests + JaCoCo') {
            steps { script { run('python build.py test') } }
        }
        stage('Coverage gate') {
            steps { script { run('python build.py coverage') } }
        }
        stage('Mutation testing (PIT)') {
            steps { script { run('python build.py mutation') } }
        }
        stage('Static analysis (PMD · SpotBugs)') {
            steps { script { run('python build.py pmd spotbugs') } }
        }
        stage('Requirements traceability') {
            steps { script { run('python build.py trace') } }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: 'build/reports/junit/TEST-junit-jupiter.xml'
            archiveArtifacts allowEmptyArchive: true, artifacts: 'build/reports/**, docs/traceability.md'
        }
    }
}

def run(String cmd) {
    if (isUnix()) {
        sh cmd
    } else {
        bat cmd
    }
}
