package example.armeria.grpc.envoy;

import static example.armeria.grpc.envoy.Main.configureEnvoy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.grpc.envoy.Hello.HelloReply;
import example.armeria.grpc.envoy.Hello.HelloRequest;

@Testcontainers(disabledWithoutDocker = true)
class GrpcEnvoyProxyTest {

    // the port envoy binds to within the container
    private static final int envoyPort = 10000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new HelloService())
                                  .build());
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"H1C", "H2C"})
    void reverseProxy(SessionProtocol sessionProtocol) {
        try (EnvoyContainer envoy = configureEnvoy(server.httpPort(), envoyPort)) {
            envoy.start();
            final String uri = sessionProtocol.uriText() + "://" + envoy.getHost() +
                               ':' + envoy.getMappedPort(envoyPort);
            // if envoy isn't ready for requests, a `UNAVAILABLE: no healthy upstream` is returned
            final RetryRule retryRule = RetryRule.builder().onServerErrorStatus().thenBackoff();
            final HelloServiceGrpc.HelloServiceBlockingStub helloService =
                    GrpcClients.builder(uri)
                               .decorator(RetryingClient.newDecorator(retryRule))
                               .build(HelloServiceGrpc.HelloServiceBlockingStub.class);
            final HelloReply reply =
                    helloService.hello(HelloRequest.newBuilder()
                                                   .setName("Armeria")
                                                   .build());
            assertThat(reply.getMessage()).isEqualTo("Hello, Armeria!");
        }
    }
}
