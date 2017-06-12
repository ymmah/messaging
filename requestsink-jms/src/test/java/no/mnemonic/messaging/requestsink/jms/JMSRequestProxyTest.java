package no.mnemonic.messaging.requestsink.jms;

import no.mnemonic.commons.container.ComponentContainer;
import no.mnemonic.messaging.requestsink.RequestContext;
import no.mnemonic.messaging.requestsink.RequestSink;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.jms.*;
import javax.naming.NamingException;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class JMSRequestProxyTest extends AbstractJMSRequestTest {

  @Mock
  private RequestSink endpoint;

  private JMSRequestProxy requestProxy;
  private ComponentContainer container;

  private Destination queue;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    //set up a real JMS connection to a vm-local activemq
    JMSConnection connection = createConnection();
    String queueName = "dynamicQueues/" + generateCookie(10);

    //set up request sink pointing at a vm-local topic
    requestProxy = JMSRequestProxy.builder()
            .addConnection(connection)
            .setDestinationName(queueName)
            .setRequestSink(endpoint)
            .build();

    Future<Void> proxyConnected = listenForProxyConnection();

    container = ComponentContainer.create(requestProxy, connection);
    container.initialize();

    session = createSession(false);
    queue = createDestination(queueName);
    //wait for proxy to connect
    proxyConnected.get(1000, TimeUnit.MILLISECONDS);
  }

  @After
  public void tearDown() throws Exception {
    container.destroy();
    if (testConnection != null) testConnection.close();
  }

  @Test
  public void testSignalSubmitsMessage() throws Exception {
    //listen for signal invocation
    Future<MessageAndContext> expectedSignal = expectSignal();
    //send testmessage
    TestMessage sentMessage = new TestMessage("test1");
    signal(sentMessage, 1000);
    //wait for signal to come through and validate
    TestMessage receivedMessage = expectedSignal.get(1000, TimeUnit.MILLISECONDS).msg;
    assertEquals(sentMessage.getCallID(), receivedMessage.getCallID());
    assertEquals(sentMessage, receivedMessage);
  }

  @Test
  public void testSignalContextEOSReturnsEOSMessage() throws Exception {
    when(endpoint.signal(any(), any(), anyLong())).thenAnswer(i -> {
      RequestContext ctx = i.getArgument(1);
      ctx.endOfStream();
      return ctx;
    });
    TestMessage sentMessage = new TestMessage("test1");
    Destination responseQueue = signal(sentMessage, 1000);
    Message eosMessage = receiveFrom(responseQueue).poll(1000, TimeUnit.MILLISECONDS);
    Assert.assertEquals(JMSRequestProxy.MESSAGE_TYPE_STREAM_CLOSED, eosMessage.getStringProperty(JMSRequestProxy.PROPERTY_MESSAGE_TYPE));
    assertEquals(sentMessage.getCallID(), eosMessage.getJMSCorrelationID());
    assertEquals(ProtocolVersion.V1, JMSUtils.getProtocolVersion(eosMessage));
  }


  @Test
  public void testSignalSingleResponse() throws Exception {
    doTestSignalResponse(1);
  }

  @Test
  public void testSignalMultipleResponses() throws Exception {
    doTestSignalResponse(100);
  }

  @Test
  public void testExtendWait() throws Exception {
    when(endpoint.signal(any(), any(), anyLong())).thenAnswer(inv -> {
      RequestContext ctx = inv.getArgument(1);
      for (int i = 0; i < 10; i++) {
        Thread.sleep(500);
        ctx.keepAlive(System.currentTimeMillis() + 1000);
      }
      ctx.addResponse(new TestMessage("resp"));
      ctx.endOfStream();
      return ctx;
    });

    TestMessage sentMessage = new TestMessage("test1");
    Destination responseQueue = signal(sentMessage, 1000);
    System.out.println("Timeout at " + new Date(System.currentTimeMillis() + 1000));
    BlockingQueue<Message> response = receiveFrom(responseQueue);

    for (int i = 0; i < 10; i++) {
      Message r = response.poll(1000, TimeUnit.MILLISECONDS);
      assertEquals(JMSRequestProxy.MESSAGE_TYPE_EXTEND_WAIT, r.getStringProperty(JMSRequestProxy.PROPERTY_MESSAGE_TYPE));
      assertEquals(ProtocolVersion.V1, JMSUtils.getProtocolVersion(r));
      assertEquals(sentMessage.getCallID(), r.getJMSCorrelationID());
      long until = r.getLongProperty(JMSRequestProxy.PROPERTY_REQ_TIMEOUT);
      assertTrue(until > System.currentTimeMillis());
      System.out.println("Extending until " + new Date(until));
    }
    Message respMessage = response.poll(1000, TimeUnit.MILLISECONDS);
    assertEquals(JMSRequestProxy.MESSAGE_TYPE_SIGNAL_RESPONSE, respMessage.getStringProperty(JMSRequestProxy.PROPERTY_MESSAGE_TYPE));
    assertEquals("resp", ((TestMessage) JMSUtils.extractObject(respMessage)).getId());

    Message eosMessage = response.poll(1000, TimeUnit.MILLISECONDS);
    Assert.assertEquals(JMSRequestProxy.MESSAGE_TYPE_STREAM_CLOSED, eosMessage.getStringProperty(JMSRequestProxy.PROPERTY_MESSAGE_TYPE));
  }

  @Test
  public void testChannelUpload() throws Exception {
    TestMessage sentMessage = new TestMessage("a bit longer message which is fragmented");
    //listen for signal invocation
    Future<MessageAndContext> expectedSignal = expectSignal();
    //request channel
    Destination responseQueue = requestChannel(sentMessage.getCallID(), 1000);
    BlockingQueue<Message> responses = receiveFrom(responseQueue);

    //receive channel setup
    Message channelSetup = responses.poll(1000, TimeUnit.MILLISECONDS);
    assertEquals(JMSRequestProxy.MESSAGE_TYPE_CHANNEL_SETUP, channelSetup.getStringProperty(JMSRequestProxy.PROPERTY_MESSAGE_TYPE));
    assertEquals(sentMessage.getCallID(), channelSetup.getJMSCorrelationID());
    Destination channel = channelSetup.getJMSReplyTo();

    //fragment and upload data through channel
    uploadAndCloseChannel(channel, sentMessage.getCallID(), JMSUtils.serialize(sentMessage), 10);

    //wait for signal to come through after upload and validate
    TestMessage receivedMessage = expectedSignal.get(1000, TimeUnit.MILLISECONDS).msg;
    assertEquals(sentMessage.getCallID(), receivedMessage.getCallID());
    assertEquals(sentMessage, receivedMessage);
  }

  //private methods

  private void doTestSignalResponse(int numberOfResponses) throws NamingException, JMSException, IOException, InterruptedException {
    when(endpoint.signal(any(), any(), anyLong())).thenAnswer(inv -> {
      RequestContext ctx = inv.getArgument(1);
      for (int i = 0; i < numberOfResponses; i++) {
        ctx.addResponse(new TestMessage("resp" + i));
      }
      ctx.endOfStream();
      return ctx;
    });

    TestMessage sentMessage = new TestMessage("test1");
    Destination responseQueue = signal(sentMessage, 1000);
    BlockingQueue<Message> response = receiveFrom(responseQueue);

    for (int i = 0; i < numberOfResponses; i++) {
      Message r = response.poll(1000, TimeUnit.MILLISECONDS);
      assertNotNull(r);
      assertEquals(JMSRequestProxy.MESSAGE_TYPE_SIGNAL_RESPONSE, r.getStringProperty(JMSRequestProxy.PROPERTY_MESSAGE_TYPE));
      assertEquals(ProtocolVersion.V1, JMSUtils.getProtocolVersion(r));
      assertEquals(sentMessage.getCallID(), r.getJMSCorrelationID());
      assertEquals("resp" + i, ((TestMessage) JMSUtils.extractObject(r)).getId());
    }
    Message eosMessage = response.poll(1000, TimeUnit.MILLISECONDS);
    Assert.assertEquals(JMSRequestProxy.MESSAGE_TYPE_STREAM_CLOSED, eosMessage.getStringProperty(JMSRequestProxy.PROPERTY_MESSAGE_TYPE));
  }

  private Future<MessageAndContext> expectSignal() {
    CompletableFuture<MessageAndContext> f = new CompletableFuture<>();
    when(endpoint.signal(any(), any(), anyLong())).thenAnswer(i -> {
      TestMessage signal = i.getArgument(0);
      RequestContext ctx = i.getArgument(1);
      f.complete(new MessageAndContext(signal, ctx));
      return ctx;
    });
    return f;
  }

  static class MessageAndContext {
    private TestMessage msg;
    private RequestContext ctx;

    MessageAndContext(TestMessage msg, RequestContext ctx) {
      this.msg = msg;
      this.ctx = ctx;
    }
  }

  private Future<Void> listenForProxyConnection() {
    CompletableFuture<Void> future = new CompletableFuture<>();
    JMSRequestProxy.JMSRequestProxyConnectionListener connectionListener = mock(JMSRequestProxy.JMSRequestProxyConnectionListener.class);
    requestProxy.addJMSRequestProxyConnectionListener(connectionListener);
    doAnswer(i -> future.complete(null)).when(connectionListener).connected(any());
    return future;
  }

  private BlockingQueue<Message> receiveFrom(Destination destination) throws NamingException, JMSException {
    BlockingQueue<Message> q = new LinkedBlockingDeque<>();
    MessageConsumer consumer = session.createConsumer(destination);
    consumer.setMessageListener(q::add);
    return q;
  }

  private Destination signal(no.mnemonic.messaging.requestsink.Message msg, long timeout) throws NamingException, JMSException, IOException {
    Destination responseQueue = session.createTemporaryQueue();
    Message message = byteMsg(msg, JMSRequestProxy.MESSAGE_TYPE_SIGNAL, msg.getCallID());
    message.setLongProperty(JMSRequestProxy.PROPERTY_REQ_TIMEOUT, System.currentTimeMillis() + timeout);
    message.setJMSReplyTo(responseQueue);
    MessageProducer producer = session.createProducer(queue);
    producer.send(message);
    producer.close();
    return responseQueue;
  }

  private void uploadAndCloseChannel(Destination channel, String callID, byte[] data, int maxSize) throws Exception {
    MessageProducer producer = session.createProducer(channel);
    String md5sum = JMSUtils.md5(data);
    List<byte[]> fragments = JMSUtils.splitArray(data, maxSize);
    int idx = 0;
    for (byte[] f : fragments) {
      Message message = byteMsg(f, JMSRequestProxy.MESSAGE_TYPE_SIGNAL_FRAGMENT, callID);
      message.setIntProperty(JMSRequestProxy.PROPERTY_FRAGMENTS_IDX, idx++);
      message.setLongProperty(JMSRequestProxy.PROPERTY_REQ_TIMEOUT, System.currentTimeMillis() + 10000);
      producer.send(message);
    }
    Message eos = textMsg("channel end", JMSRequestProxy.MESSAGE_TYPE_STREAM_CLOSED, callID);
    eos.setIntProperty(JMSRequestProxy.PROPERTY_FRAGMENTS_TOTAL, idx);
    eos.setStringProperty(JMSRequestProxy.PROPERTY_DATA_CHECKSUM_MD5, md5sum);
    producer.send(eos);
    producer.close();
  }

  private Destination requestChannel(String callID, long timeout) throws Exception {
    Destination responseQueue = session.createTemporaryQueue();
    Message message = textMsg("channel request", JMSRequestProxy.MESSAGE_TYPE_CHANNEL_REQUEST, callID);
    message.setLongProperty(JMSRequestProxy.PROPERTY_REQ_TIMEOUT, System.currentTimeMillis() + timeout);
    message.setJMSReplyTo(responseQueue);
    MessageProducer producer = session.createProducer(queue);
    producer.send(message);
    producer.close();
    return responseQueue;
  }


}