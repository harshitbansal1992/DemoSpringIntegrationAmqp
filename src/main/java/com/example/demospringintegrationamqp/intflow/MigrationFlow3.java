package com.example.demospringintegrationamqp.intflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.router.ErrorMessageExceptionTypeRouter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandlingException;

import java.time.LocalDateTime;


/**
 * Spring boot 3 compatible class
 */
@Configuration
@EnableRabbit
@EnableIntegration
public class MigrationFlow3 {

    @Bean
    public MessageConverter jsonMessageConverter() {
        final var objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    RabbitTemplate rabbitTemplate(CachingConnectionFactory connectionFactory, MessageConverter messageConverter) {
        final var rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        return rabbitTemplate;
    }

    @Bean
    public AmqpAdmin amqpAdmin(final RabbitTemplate rabbitTemplate) {
        return new RabbitAdmin(rabbitTemplate);
    }

    @Bean
    public MessageChannel consumeUserMigrationQueueChannel() {
        return MessageChannels.direct().getObject();
    }

    @Bean
    public MessageChannel errorChannel() {
        return MessageChannels.direct().getObject();
    }

    @Bean
    public MessageChannel errorMessageChannel() {
        return MessageChannels.direct().getObject();
    }

    @Bean
    public MessageChannel deadLetterQueueChannel() {
        return MessageChannels.direct().getObject();
    }



    @Bean(name = "errorMessageExceptionTypeRouter")
    public ErrorMessageExceptionTypeRouter errorMessageExceptionTypeRouter() {
        ErrorMessageExceptionTypeRouter router = new ErrorMessageExceptionTypeRouter();
        router.setChannelMapping(RuntimeException.class.getName(), "errorMessageChannel");

        return router;
    }

    @Bean
    public IntegrationFlow exceptionHandler(@Qualifier("errorMessageExceptionTypeRouter") final ErrorMessageExceptionTypeRouter errorMessageExceptionTypeRouter,
                                            final MessageChannel errorChannel) {
        return IntegrationFlow.from(errorChannel)
                .handle(errorMessageExceptionTypeRouter)
                .get();
    }

    @Bean
    public IntegrationFlow checkRetryCountAndRouteSubflow(final MessageChannel errorMessageChannel,
                                                          final IntegrationFlow sendToRetryExchange,
                                                          final MessageChannel deadLetterQueueChannel,
                                                          final RabbitTemplate rabbitTemplate) {
        return IntegrationFlow.from(errorMessageChannel)
                .handle(MessageHandlingException.class, (m, h) -> {
                    var payload = ((MyPojo)m.getFailedMessage().getPayload());
                    int retryCount = payload.getRetryCount();
                    payload.setRetryCount(retryCount + 1);

                    return ((MyPojo)m.getFailedMessage().getPayload());
                })
               .enrichHeaders(h -> h.headerFunction("RETRY_COUNT_HEADER", m -> {
                    var retryCount = m.getHeaders().get("RETRY_COUNT_HEADER", Integer.class);
                    if (retryCount != null) {
                        return retryCount + 1;
                    }
                    return 1;
                }, true))
                .route(MyPojo.class,
                        m ->  {
                            System.out.println("retry: " + LocalDateTime.now());
                            if(m.getRetryCount() == 10) {
                                return true;
                            } else {
                                return false;
                            }
                        },
                        m -> m.subFlowMapping(true, subflow -> subflow.channel(deadLetterQueueChannel))
                                .subFlowMapping(false, sendToRetryExchange)
                )
                .get();

    }


    @Bean
    IntegrationFlow sendToRetryExchange(final RabbitTemplate rabbitTemplate) {
        return f -> f.handle(
                Amqp.outboundGateway(rabbitTemplate)
                        .exchangeName("migration.rxo")
                        .routingKey(""), e -> e.requiresReply(false));
    }

    @Bean
    public IntegrationFlow sendToDeadLetterQueue(final MessageChannel deadLetterQueueChannel,
                                                 final RabbitTemplate rabbitTemplate) {
        return IntegrationFlow.from(deadLetterQueueChannel)
                .handle(Amqp.outboundGateway(rabbitTemplate)
                                .exchangeName("migration.dlx"),
                        e -> e.requiresReply(false))
                .get();
    }

    @Bean
    SimpleMessageListenerContainer userMigrationQueueMessageListenerContainer(final CachingConnectionFactory connectionFactory, RabbitTemplate rabbitTemplate) {
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);

        SimpleMessageListenerContainer listenerContainer = new SimpleMessageListenerContainer(connectionFactory);
        listenerContainer.setConcurrentConsumers(1);
        listenerContainer.addQueueNames("migration.q");
        listenerContainer.setAcknowledgeMode(AcknowledgeMode.AUTO);
        listenerContainer.setShutdownTimeout(10000);
        return listenerContainer;
    }

    @Bean
    public IntegrationFlow consumeUserMigrationQueueSubflow(@Qualifier("userMigrationQueueMessageListenerContainer") final SimpleMessageListenerContainer userMigrationQueueMessageListenerContainer,
                                                            final RabbitTemplate rabbitTemplate,
                                                            final MessageChannel errorChannel,
                                                            AmqpAdmin admin) {
        return IntegrationFlow.from(
                        Amqp.inboundGateway(userMigrationQueueMessageListenerContainer).errorChannel(errorChannel))
                .enrichHeaders(h -> h.headerFunction("RETRY_COUNT_HEADER", m -> {
                    System.out.println("received: " + LocalDateTime.now());
                    var retryCount = m.getHeaders().get("RETRY_COUNT_HEADER", Integer.class);
                    if (retryCount != null) {
                        return retryCount + 1;
                    }
                    return 0;
                }, true))
                .transform(Transformers.fromJson(MyPojo.class))
                .handle(MyPojo.class, (message, headers) -> {
                    System.out.println("My event : " + message.getEvent());

                    throw new RuntimeException();

                })
                .get();
    }
}

