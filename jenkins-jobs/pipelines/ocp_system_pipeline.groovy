pipeline {
    agent {
        label 'Slave'
    }

    stages {
        stage('Checkout - Debezium') {
            steps {
                checkout([
                        $class           : 'GitSCM',
                        branches         : [[name: "${DBZ_GIT_BRANCH}"]],
                        userRemoteConfigs: [[url: "${DBZ_GIT_REPOSITORY}"]],
                        extensions       : [[$class           : 'RelativeTargetDirectory',
                                             relativeTargetDir: 'debezium']],
                ])
            }
        }

        stage('Prepare project') {
            steps {
                withCredentials([
                        usernamePassword(credentialsId: "${OCP_CREDENTIALS}", usernameVariable: 'OCP_USERNAME', passwordVariable: 'OCP_PASSWORD'),
                        file(credentialsId: "${PULL_SECRET}", variable: 'SECRET_PATH'),
                ]) {
                    sh '''
                    oc login -u "${OCP_USERNAME}" -p "${OCP_PASSWORD}" --insecure-skip-tls-verify=true "${OCP_URL}"
                    oc new-project "${OCP_PROJECT_NAME}-testsuite" || oc project "${OCP_PROJECT_NAME}-testsuite"
                    oc adm policy add-cluster-role-to-user cluster-admin "system:serviceaccount:${OCP_PROJECT_NAME}-testsuite:default"
                    oc apply -f "${SECRET_PATH}"
                    # TODO parse secret name ?
                    '''
                }
            }
        }

        stage('Run tests') {
            steps {
                withCredentials([
                        file(credentialsId: "${PULL_SECRET}", variable: 'SECRET_PATH'),
                ]) {
                    sh '''
                    GROUPS_ARG="!docker"
                    if [ ${TEST_APICURIO_REGISTRY} == false ]; then
                        GROUPS_ARG="${GROUPS_ARG} & !avro"
                    fi

                    FILENAME="testsuite-job"

                    OPTIONAL_PARAMS=""
                    if [ ! -z  "${DBZ_CONNECT_IMAGE}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --dbz-connect-image ${DBZ_CONNECT_IMAGE}"
                    fi

                    if [ ! -z  "${ARTIFACT_SERVER_IMAGE}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --artifact-server-image ${ARTIFACT_SERVER_IMAGE}"
                    fi

                    if [ ! -z  "${APICURIO_VERSION}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --apicurio-version ${APICURIO_VERSION}"
                    fi

                    if [ ! -z  "${STRZ_GIT_REPOSITORY}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --strz-git-repository ${STRZ_GIT_REPOSITORY}"
                    fi

                    if [ ! -z  "${STRZ_GIT_BRANCH}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --strz-git-branch ${STRZ_GIT_BRANCH}"
                    fi

                    if [ ! -z  "${STRZ_DOWNSTREAM_URL}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --strz-downstream-url ${STRZ_DOWNSTREAM_URL}"
                    fi

                    if [ ! -z  "${APIC_GIT_REPOSITORY}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --apic-git-repository ${APIC_GIT_REPOSITORY}"
                    fi

                    if [ ! -z  "${APIC_GIT_BRANCH}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --apic-git-branch ${APIC_GIT_BRANCH}"
                    fi

                    if [ ! -z  "${APIC_DOWNSTREAM_URL}" ]; then
                        OPTIONAL_PARAMS="$OPTIONAL_PARAMS --apic-downstream-url ${APIC_DOWNSTREAM_URL}"
                    fi

                    cd ${WORKSPACE}/debezium
                    jenkins-jobs/docker/debezium-testing-system/deployment-template.sh --filename "${FILENAME}" \
                    --pull-secret-name "${PULL_SECRET_NAME}" \
                    --docker-tag "${DOCKER_TAG}" \
                    --project-name "${OCP_PROJECT_NAME}" \
                    --product-build "${PRODUCT_BUILD}" \
                    --strimzi-kc-build ${STRIMZI_KC_BUILD} \
                    --apicurio-version "${APICURIO_VERSION}" \
                    --kafka-version "${KAFKA_VERSION}" \
                    --groups-arg "${GROUPS_ARG}" \
                    ${OPTIONAL_PARAMS}
                    oc delete -f "${FILENAME}.yml" --ignore-not-found
                    oc create -f "${FILENAME}.yml"

                    # wait for the job to finish, print logs. Only one running testsuite expected
                    pod_name=$(oc get pods | grep testsuite | head -n1| awk '{print $1;}')

                    oc wait --timeout=300s --for=condition=Running pod/${pod_name}

                    oc logs -f ${pod_name}
                    '''
                }
            }
        }
    }
}
