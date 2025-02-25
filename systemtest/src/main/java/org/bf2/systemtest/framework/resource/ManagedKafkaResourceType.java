package org.bf2.systemtest.framework.resource;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.framework.KeycloakInstance;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ManagedKafkaResourceType implements ResourceType<ManagedKafka> {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaResourceType.class);


    public static MixedOperation<ManagedKafka, KubernetesResourceList<ManagedKafka>, Resource<ManagedKafka>> getOperation() {
        return KubeClient.getInstance().client().resources(ManagedKafka.class);
    }

    @Override
    public Resource<ManagedKafka> resource(KubeClient client, ManagedKafka resource) {
        return client.client().resources(ManagedKafka.class).inNamespace(resource.getMetadata().getNamespace()).withName(resource.getMetadata().getName());
    }

    @Override
    public Predicate<ManagedKafka> readiness(KubeClient client) {
        AtomicInteger count = new AtomicInteger();
        Set<String> messages = Collections.synchronizedSet(new LinkedHashSet<>());
        return mk -> {
            if (mk == null) {
                throw new IllegalStateException("ManagedKafka is null");
            }

            ManagedKafkaCondition mkc = getCondition(mk.getStatus(), ManagedKafkaCondition.Type.Ready).orElse(null);
            if (mkc == null) {
                return false;
            }
            if (ManagedKafkaCondition.Status.True.name().equals(mkc.getStatus())) {
                return true;
            }
            if (ManagedKafkaCondition.Reason.Error.name().equals(mkc.getReason())) {
                if (messages.add(mkc.getMessage())) {
                    LOGGER.warn("ManagedKafka {} in error state {}", mk.getMetadata().getName(), mkc.getMessage());
                }
                //throw new IllegalStateException(String.format("ManagedKafka %s in error state %s", mk.getMetadata().getName(), mkc.getMessage()));
            }
            if (count.getAndIncrement() % 15 == 0) {
                ListOptions opts = new ListOptionsBuilder().withFieldSelector("status.phase=Pending").build();
                client.client().pods().inNamespace(mk.getMetadata().getNamespace()).withLabel("strimzi.io/cluster").list(opts).getItems().forEach(ManagedKafkaResourceType::checkUnschedulablePod);
            }
            return false;
        };
    }

    /**
     * @return true if and only if there is a condition of the given type with the given status
     */
    public static boolean hasConditionStatus(ManagedKafka mk, ManagedKafkaCondition.Type type, ManagedKafkaCondition.Status status) {
        if (mk == null) {
            return false;
        }
        return hasConditionStatus(mk.getStatus(), type, status);
    }

    public static boolean hasConditionStatus(ManagedKafkaStatus mks, ManagedKafkaCondition.Type type, ManagedKafkaCondition.Status status) {
        return getCondition(mks, type).map(mkc -> status.name().equals(mkc.getStatus())).orElse(false);
    }

    public static Optional<ManagedKafkaCondition> getCondition(ManagedKafkaStatus mks, ManagedKafkaCondition.Type type) {
        if (mks == null || mks.getConditions() == null) {
            return Optional.empty();
        }
        return mks.getConditions().stream().filter(mkc -> type.name().equals(mkc.getType())).findFirst();
    }

    public static Pod getCanaryPod(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "canary"))).findFirst().get();
    }

    public static List<Pod> getKafkaPods(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "kafka")) &&
                        !pod.getMetadata().getName().contains("exporter")).collect(Collectors.toList());
    }

    public static List<Pod> getKafkaExporterPods(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "kafka-exporter"))).collect(Collectors.toList());
    }

    public static List<Pod> getZookeeperPods(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "zookeeper"))).collect(Collectors.toList());
    }

    public static Pod getAdminApiPod(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "admin-server"))).findFirst().get();
    }

    /**
     * get common default managedkafka instance
     *
     * @throws Exception
     */
    public static ManagedKafka getDefault(String namespace, String appName, KeycloakInstance keycloak, String strimziVersion) throws Exception {
        final String tlsCert;
        final String tlsKey;

        String hostDomain = SystemTestEnvironment.BOOTSTRAP_HOST_DOMAIN;
        if (!KubeClient.getInstance().isGenericKubernetes()) {
            OpenShiftClient cli = KubeClient.getInstance().client().adapt(OpenShiftClient.class);
            hostDomain = Optional.ofNullable(
                            cli.operator().ingressControllers()
                                    .inNamespace("openshift-ingress-operator")
                                    .withName("sharded").get())
                    .orElse(cli.operator().ingressControllers()
                            .inNamespace("openshift-ingress-operator")
                            .withName("default").get())
                    .getStatus().getDomain();
        }

        if (SystemTestEnvironment.DUMMY_CERT.equals(SystemTestEnvironment.ENDPOINT_TLS_CERT)) {
            SecurityUtils.TlsConfig tlsConfig = SecurityUtils.getTLSConfig(hostDomain);
            tlsCert = tlsConfig.getCert();
            tlsKey = tlsConfig.getKey();
        } else {
            tlsCert = SystemTestEnvironment.ENDPOINT_TLS_CERT;
            tlsKey = SystemTestEnvironment.ENDPOINT_TLS_KEY;
        }

        final String oauthClientId;
        final String oauthTlsCert;
        final String oauthClientSecret;
        final String oauthUserClaim;
        final String oauthFallbackUserClaim;
        final String oauthJwksEndpoint;
        final String oauthTokenEndpoint;
        final String oauthIssuerEndpoint;

        if (keycloak != null) {
            oauthClientId = "kafka";
            oauthTlsCert = keycloak.getKeycloakCert();
            oauthClientSecret = "kafka";
            oauthUserClaim = keycloak.getUserNameClaim();
            oauthFallbackUserClaim = keycloak.getFallbackUserNameClaim();
            oauthJwksEndpoint = keycloak.getJwksEndpointUri();
            oauthTokenEndpoint = keycloak.getOauthTokenEndpointUri();
            oauthIssuerEndpoint = keycloak.getValidIssuerUri();
        } else if (SystemTestEnvironment.DUMMY_OAUTH_JWKS_URI.equals(SystemTestEnvironment.OAUTH_JWKS_ENDPOINT)) {
            oauthClientId = null;
            oauthTlsCert = null;
            oauthClientSecret = null;
            oauthUserClaim = null;
            oauthFallbackUserClaim = null;
            oauthJwksEndpoint = null;
            oauthTokenEndpoint = null;
            oauthIssuerEndpoint = null;
        } else {
            //use defined values by env vars for oauth
            oauthClientId = SystemTestEnvironment.OAUTH_CLIENT_ID;
            oauthTlsCert = SystemTestEnvironment.DUMMY_CERT.equals(SystemTestEnvironment.OAUTH_TLS_CERT) ? null : SystemTestEnvironment.OAUTH_TLS_CERT;
            oauthClientSecret = SystemTestEnvironment.OAUTH_CLIENT_SECRET;
            oauthUserClaim = SystemTestEnvironment.OAUTH_USER_CLAIM;
            oauthFallbackUserClaim = SystemTestEnvironment.OAUTH_FALLBACK_USER_CLAIM;
            oauthJwksEndpoint = SystemTestEnvironment.OAUTH_JWKS_ENDPOINT;
            oauthTokenEndpoint = SystemTestEnvironment.OAUTH_TOKEN_ENDPOINT;
            oauthIssuerEndpoint = SystemTestEnvironment.OAUTH_ISSUER_ENDPOINT;
        }

        return ManagedKafka.getDefault(appName,
                namespace,
                hostDomain,
                tlsCert,
                tlsKey,
                oauthClientId,
                oauthTlsCert,
                oauthClientSecret,
                oauthUserClaim,
                oauthFallbackUserClaim,
                oauthJwksEndpoint,
                oauthTokenEndpoint,
                oauthIssuerEndpoint,
                strimziVersion);
    }

    public static void isDeleted(ManagedKafka mk) {
        TestUtils.waitFor("Managed kafka is removed", 1_000, 600_000, () -> {
            ManagedKafka m = ManagedKafkaResourceType.getOperation().inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get();
            List<Pod> pods = KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems();
            return m == null && pods.size() == 0;
        });
    }

    private static void checkUnschedulablePod(Pod p) {
        p.getStatus().getConditions().stream().filter(c -> "PodScheduled".equals(c.getType()) && "False".equals(c.getStatus()) && "Unschedulable".equals(c.getReason())).forEach(c -> {
            LOGGER.info("Pod {} unschedulable {}", p.getMetadata().getName(), c.getMessage());
            //throw new UnschedulablePodException(String.format("Unschedulable pod %s : %s", p.getMetadata().getName(), c.getMessage()));
        });
    }

    public static boolean isDevKafka(ManagedKafka mk) {
        var kafkacli = KubeClient.getInstance().client().resources(Kafka.class);
        return kafkacli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get().getMetadata().getLabels().containsKey("dev-kafka");
    }
}
