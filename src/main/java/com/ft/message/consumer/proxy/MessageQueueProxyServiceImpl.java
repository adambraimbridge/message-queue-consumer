package com.ft.message.consumer.proxy;

import com.ft.message.consumer.MessageQueueConsumer;
import com.ft.message.consumer.QueueProxyClientSingleton;
import com.ft.message.consumer.config.MessageQueueConsumerConfiguration;
import com.ft.message.consumer.proxy.model.CreateConsumerInstanceResponse;
import com.ft.message.consumer.proxy.model.MessageRecord;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.List;

import static com.ft.message.consumer.QueueProxyClientSingleton.*;
import static org.apache.commons.lang.exception.ExceptionUtils.getStackTrace;

public class MessageQueueProxyServiceImpl implements MessageQueueProxyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageQueueProxyServiceImpl.class);

    private static final String PROXY_ERR = "Unable to %s. Proxy error.";
    private static final String PROXY_STATUS_ERR = "Unable to %s. Proxy returned %d";
    private static final String CREATE = "create consumer instance";
    private static final String CONSUME = "consume messages";
    private static final String COMMIT = "commit offsets";
    private static final String DESTROY = "destroy consumer instance";

    private static final int SC_NO_CONTENT = ClientResponse.Status.NO_CONTENT.getStatusCode();
    private static final int SC_OK = ClientResponse.Status.OK.getStatusCode();

    private MessageQueueConsumerConfiguration configuration;
    private Client proxyClient;
    private String status = String.format(MESSAGES_CONSUMED, 0);

    private Environment env;
    private JerseyClientConfiguration jerseyConfig;
    private String queueProxyClientName;

    public MessageQueueProxyServiceImpl(MessageQueueConsumerConfiguration configuration, Client proxyClient) {
        this.configuration = configuration;
        this.proxyClient = getQueueProxyClientSingleInstance(proxyClient, null, null, null);
    }

    public MessageQueueProxyServiceImpl(MessageQueueConsumerConfiguration configuration, Client proxyClient,
                                        Environment env,
                                        JerseyClientConfiguration jerseyConfig,
                                        String queueProxyClientName) {
        this.env = env;
        this.jerseyConfig = jerseyConfig;
        this.queueProxyClientName = queueProxyClientName;
        this.configuration = configuration;
        this.proxyClient = getQueueProxyClientSingleInstance(proxyClient, env, jerseyConfig, queueProxyClientName);
    }

    private void checkStatus(ClientResponse response, int expectedStatus, String action) {
        if (response.getStatus() != expectedStatus) {
            String msg = String.format(PROXY_STATUS_ERR, action, response.getStatus());
            updateUnhealthyStatus(msg);
            throw new QueueProxyServiceException(msg);
        }
    }

    private QueueProxyServiceException proxyException(Throwable e, String action) {
        String msg = String.format(PROXY_ERR, action);
        updateUnhealthyStatus(msg);
        return new QueueProxyServiceException(msg, e);
    }

    @Override
    public URI createConsumerInstance() {
        ClientResponse clientResponse = null;
        try {
            URI uri = UriBuilder.fromUri(configuration.getQueueProxyHost())
                    .path("consumers")
                    .path(configuration.getGroupName())
                    .build();

            WebResource.Builder builder = getQueueProxyClientSingleInstance(proxyClient, env, jerseyConfig, queueProxyClientName).resource(uri).getRequestBuilder();
            builder.header("Content-Type", "application/json");
            if (queueIsNotEmpty()) {
                builder.header("Host", configuration.getQueue());
            }
            clientResponse = builder.post(ClientResponse.class, String.format("{\"auto.offset.reset\": \"%s\", \"auto.commit.enable\": \"%b\"}", configuration.getOffsetReset(), configuration.isAutoCommit()));
            checkStatus(clientResponse, SC_OK, CREATE);
            return clientResponse.getEntity(CreateConsumerInstanceResponse.class).getBaseUri();
        } catch (ClientHandlerException | UniformInterfaceException e) {
            throw proxyException(e, CREATE);
        } catch (QueueProxyServiceException e) {
            LOGGER.info(">>>> createConsumerInstance QueueProxyServiceException from checkStatus: " + "\n" +
                    "exception stacktrace: " + getStackTrace(e)  + "\n");
            throw e;
        } finally {
            if (clientResponse != null) {
                clientResponse.close();
            }
        }
    }

    @Override
    public void destroyConsumerInstance(URI consumerInstance) {
        ClientResponse clientResponse = null;
        try {

            UriBuilder uriBuilder = UriBuilder.fromUri(consumerInstance);
            if (queueIsNotEmpty()) {
                addProxyPortAndHostInUri(uriBuilder);
            }
            URI uri = uriBuilder.build();

            WebResource.Builder builder = getQueueProxyClientSingleInstance(proxyClient, env, jerseyConfig, queueProxyClientName).resource(uri).getRequestBuilder();
            if (queueIsNotEmpty()) {
                builder.header("Host", configuration.getQueue());
            }

            clientResponse = builder.delete(ClientResponse.class);
            LOGGER.info(">>>> checkStatus got response when trying to delete Kafka consumer instance with status: " + clientResponse.getStatus());
            checkStatus(clientResponse, SC_NO_CONTENT, DESTROY);
            updateUnhealthyStatus("Consumer has been destroyed.");
            //
            proxyClient.destroy();
            proxyClient = null;
        } catch (ClientHandlerException | UniformInterfaceException e) {
            throw proxyException(e, DESTROY);
        }  catch (QueueProxyServiceException e) {
            LOGGER.info(">>>> destroyConsumerInstance QueueProxyServiceException from checkStatus: " + "\n" +
                    "exception stacktrace: " + getStackTrace(e)  + "\n");
            throw e;
        } finally {
            if (clientResponse != null) {
                clientResponse.close();
            }
        }
    }

    @Override
    public List<MessageRecord> consumeMessages(URI consumerInstance) {
        ClientResponse clientResponse = null;
        try {
            UriBuilder uriBuilder = UriBuilder.fromUri(consumerInstance).path("topics")
                    .path(configuration.getTopicName());

            if (queueIsNotEmpty()) {
                addProxyPortAndHostInUri(uriBuilder);
            }

            URI uri = uriBuilder.build();

            WebResource.Builder builder = getQueueProxyClientSingleInstance(proxyClient, env, jerseyConfig, queueProxyClientName).resource(uri).getRequestBuilder();
            builder.header("Accept", "application/json");
            if (queueIsNotEmpty()) {
                builder.header("Host", configuration.getQueue());
            }
            clientResponse = builder.get(ClientResponse.class);
            checkStatus(clientResponse, SC_OK, CONSUME);

            List<MessageRecord> messages = clientResponse.getEntity(new GenericType<List<MessageRecord>>() {
            });
            updateHealthyStatus(messages.size());
            return messages;
        } catch (ClientHandlerException | UniformInterfaceException e) {
            throw proxyException(e, CONSUME);
        } finally {
            if (clientResponse != null) {
                clientResponse.close();
            }
        }
    }

    @Override
    public void commitOffsets(URI consumerInstance) {
        ClientResponse clientResponse = null;
        try {

            UriBuilder uriBuilder = UriBuilder.fromUri(consumerInstance).path("offsets");
            if (queueIsNotEmpty()) {
                addProxyPortAndHostInUri(uriBuilder);
            }
            URI uri = uriBuilder.build();

            WebResource.Builder builder = getQueueProxyClientSingleInstance(proxyClient, env, jerseyConfig, queueProxyClientName).resource(uri).getRequestBuilder();
            if (queueIsNotEmpty()) {
                builder.header("Host", configuration.getQueue());
            }

            clientResponse = builder.post(ClientResponse.class);
            checkStatus(clientResponse, SC_OK, COMMIT);
        } catch (ClientHandlerException | UniformInterfaceException e) {
            throw proxyException(e, COMMIT);
        } finally {
            if (clientResponse != null) {
                clientResponse.close();
            }
        }
    }

    private void addProxyPortAndHostInUri(UriBuilder uriBuilder) {
        URI proxyUri = UriBuilder.fromUri(configuration.getQueueProxyHost()).build();
        uriBuilder.host(proxyUri.getHost()).port(proxyUri.getPort());
    }

    private boolean queueIsNotEmpty() {
        return configuration.getQueue() != null && !configuration.getQueue().isEmpty();
    }

    private void updateHealthyStatus(int messageCount) {
        status = String.format(MESSAGES_CONSUMED, messageCount);
    }

    private void updateUnhealthyStatus(String msg) {
        status = msg;
    }

    @Override
    public String getStatus() {
        return status;
    }
}
