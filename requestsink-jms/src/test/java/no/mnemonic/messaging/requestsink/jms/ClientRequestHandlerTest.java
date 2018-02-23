package no.mnemonic.messaging.requestsink.jms;

import no.mnemonic.messaging.requestsink.RequestContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.jms.*;
import java.io.IOException;
import java.lang.IllegalStateException;
import java.util.Arrays;

import static no.mnemonic.messaging.requestsink.jms.JMSBase.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.*;

public class ClientRequestHandlerTest {

  private static final String CALL_ID = "callID";

  @Mock
  private RequestContext requestContext;
  @Mock
  private Session session;
  @Mock
  private TemporaryQueue temporaryQueue;
  @Mock
  private MessageConsumer messageConsumer;
  @Mock
  private Runnable closeListener;

  private ClientRequestHandler handler;
  private TestMessage testMessage = new TestMessage(CALL_ID);
  private byte[] messageBytes;

  @Before
  public void setup() throws JMSException, IOException {
    MockitoAnnotations.initMocks(this);
    when(session.createTemporaryQueue()).thenReturn(temporaryQueue);
    when(session.createConsumer(any())).thenReturn(messageConsumer);
    when(requestContext.isClosed()).thenReturn(false);
    when(requestContext.addResponse(any())).thenReturn(true);
    when(requestContext.keepAlive(anyLong())).thenReturn(true);
    messageBytes = JMSUtils.serialize(testMessage);
    handler = new ClientRequestHandler(CALL_ID, session, new ClientMetrics(), ClassLoader.getSystemClassLoader(), requestContext, closeListener);
  }

  @Test
  public void testHandleFragments() throws IOException {
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 0, Arrays.copyOfRange(messageBytes, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 1, Arrays.copyOfRange(messageBytes, 3, messageBytes.length))));
    assertTrue(handler.reassembleFragments("responseID", 2, JMSUtils.md5(messageBytes)));
    verify(requestContext).addResponse(eq(testMessage));
  }

  @Test
  public void testCloseListenerNotifiedOnHandlerCleanup() throws IOException {
    handler.cleanup();
    verify(closeListener).run();
  }

  @Test
  public void testMultipleFragmentedResponses() throws IOException {
    TestMessage message1 = new TestMessage("abc");
    TestMessage message2 = new TestMessage("def");
    byte[] messageBytes1 = JMSUtils.serialize(message1);
    byte[] messageBytes2 = JMSUtils.serialize(message2);

    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID1", 0, Arrays.copyOfRange(messageBytes1, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID1", 1, Arrays.copyOfRange(messageBytes1, 3, messageBytes1.length))));
    assertTrue(handler.reassembleFragments("responseID1", 2, JMSUtils.md5(messageBytes1)));

    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID2", 0, Arrays.copyOfRange(messageBytes2, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID2", 1, Arrays.copyOfRange(messageBytes2, 3, messageBytes2.length))));
    assertTrue(handler.reassembleFragments("responseID2", 2, JMSUtils.md5(messageBytes2)));

    verify(requestContext).addResponse(eq(message1));
    verify(requestContext).addResponse(eq(message2));
  }

  @Test
  public void testMultipleFragmentedResponsesOutOfOrder() throws IOException {
    TestMessage message1 = new TestMessage("abc");
    TestMessage message2 = new TestMessage("def");
    byte[] messageBytes1 = JMSUtils.serialize(message1);
    byte[] messageBytes2 = JMSUtils.serialize(message2);

    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID2", 0, Arrays.copyOfRange(messageBytes2, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID2", 1, Arrays.copyOfRange(messageBytes2, 3, messageBytes2.length))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID1", 0, Arrays.copyOfRange(messageBytes1, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID1", 1, Arrays.copyOfRange(messageBytes1, 3, messageBytes1.length))));

    assertTrue(handler.reassembleFragments("responseID2", 2, JMSUtils.md5(messageBytes2)));
    assertTrue(handler.reassembleFragments("responseID1", 2, JMSUtils.md5(messageBytes1)));

    verify(requestContext).addResponse(eq(message1));
    verify(requestContext).addResponse(eq(message2));
  }

  @Test
  public void testClosedRequestContextRejectsResponse() throws IOException {
    when(requestContext.addResponse(any())).thenReturn(false);
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 0, Arrays.copyOfRange(messageBytes, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 1, Arrays.copyOfRange(messageBytes, 3, messageBytes.length))));
    assertFalse(handler.reassembleFragments("responseID", 2, JMSUtils.md5(messageBytes)));
  }

  @Test
  public void testInvalidChecksumRejectsResponse() throws IOException {
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 0, Arrays.copyOfRange(messageBytes, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 1, Arrays.copyOfRange(messageBytes, 3, messageBytes.length))));
    assertFalse(handler.reassembleFragments("responseID", 2, "invalid"));
  }

  @Test
  public void testInvalidTotalCountRejectsResponse() throws IOException {
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 0, Arrays.copyOfRange(messageBytes, 0, 3))));
    assertTrue(handler.addFragment(new MessageFragment("callID", "responseID", 1, Arrays.copyOfRange(messageBytes, 3, messageBytes.length))));
    assertFalse(handler.reassembleFragments("responseID", 3, "invalid"));
  }

  @Test
  public void testReassembleWithoutFragmentsRejectsResponse() throws IOException {
    assertFalse(handler.reassembleFragments("responseID", 2, JMSUtils.md5(messageBytes)));
  }

  @Test
  public void testNullFragment() throws IOException {
    assertFalse(handler.addFragment(null));
  }

  @Test
  public void testSetupDoesNoInvocations() throws JMSException {
    verifyNoMoreInteractions(session);
  }

  @Test
  public void testNullMessage() throws JMSException, IOException {
    handler.handleResponse(null);
    verifyNoMoreInteractions(requestContext);
  }

  @Test
  public void testMessageWithoutProtocolVersion() throws JMSException, IOException {
    handler.handleResponse(new MockMessageBuilder<>(TextMessage.class).build());
    verifyNoMoreInteractions(requestContext);
  }

  @Test
  public void testMessageWithoutType() throws JMSException, IOException {
    handler.handleResponse(textMessage().build());
    verifyNoMoreInteractions(requestContext);
  }

  @Test
  public void testAddSingleResponse() throws JMSException, IOException {
    handler.handleResponse(createResponseMessage(CALL_ID, testMessage));
    verify(requestContext).addResponse(eq(testMessage));
  }

  @Test
  public void testAddSingleResponseWithWrongCallID() throws JMSException, IOException {
    handler.handleResponse(createResponseMessage("invalid", testMessage));
    verifyNoMoreInteractions(requestContext);
  }

  @Test
  public void testAddSingleResponseWithClosedRequestContext() throws JMSException, IOException {
    when(requestContext.isClosed()).thenReturn(true);
    handler.handleResponse(createResponseMessage(CALL_ID, testMessage));
    verify(requestContext, never()).addResponse(any());
  }

  @Test
  public void testEndOfStream() throws JMSException {
    handler.handleResponse(createEOS(CALL_ID));
    verify(requestContext).endOfStream();
  }

  @Test
  public void testErrorSignal() throws JMSException, IOException {
    handler.handleResponse(createErrorSignal(CALL_ID, new IllegalStateException()));
    verify(requestContext).notifyError(isA(IllegalStateException.class));
  }

  @Test
  public void testExtendWait() throws JMSException {
    handler.handleResponse(createExtendWaitMessage(CALL_ID, 1000));
    verify(requestContext).keepAlive(1000);
  }

  @Test
  public void testExtendWaitWithClosedRequestContext() throws JMSException {
    when(requestContext.isClosed()).thenReturn(true);
    handler.handleResponse(createExtendWaitMessage(CALL_ID, 1000));
    verify(requestContext, never()).keepAlive(anyLong());
  }

  @Test
  public void testFragmentedResponse() throws JMSException, IOException {
    TestMessage message = new TestMessage("abc");
    byte[] messageBytes = JMSUtils.serialize(message);

    handler.handleResponse(createMessageFragment(CALL_ID, "response1", Arrays.copyOfRange(messageBytes, 0, 3), 0));
    handler.handleResponse(createMessageFragment(CALL_ID, "response1", Arrays.copyOfRange(messageBytes, 3, messageBytes.length), 1));
    handler.handleResponse(createEOF(CALL_ID, "response1", 2, JMSUtils.md5(messageBytes)));

    verify(requestContext).addResponse(eq(message));
  }

  //helpers

  private BytesMessage createMessageFragment(String callID, String responseID, byte[] data, int idx) throws IOException, JMSException {
    return bytesMessage()
            .withData(data)
            .withCorrelationID(callID)
            .withProperty(PROPERTY_MESSAGE_TYPE, MESSAGE_TYPE_SIGNAL_FRAGMENT)
            .withProperty(PROPERTY_RESPONSE_ID, responseID)
            .withProperty(PROPERTY_FRAGMENTS_IDX, idx)
            .build();
  }

  private TextMessage createEOF(String callID, String responseID, int totalFragments, String checksum) throws IOException, JMSException {
    return textMessage()
            .withCorrelationID(callID)
            .withProperty(PROPERTY_MESSAGE_TYPE, MESSAGE_TYPE_END_OF_FRAGMENTED_MESSAGE)
            .withProperty(PROPERTY_RESPONSE_ID, responseID)
            .withProperty(PROPERTY_FRAGMENTS_TOTAL, totalFragments)
            .withProperty(PROPERTY_DATA_CHECKSUM_MD5, checksum)
            .build();
  }

  private BytesMessage createResponseMessage(String callID, no.mnemonic.messaging.requestsink.Message message) throws IOException, JMSException {
    byte[] data = JMSUtils.serialize(message);
    return bytesMessage()
            .withData(data)
            .withCorrelationID(callID)
            .withProperty(PROPERTY_MESSAGE_TYPE, MESSAGE_TYPE_SIGNAL_RESPONSE)
            .build();
  }

  private Message createExtendWaitMessage(String callID, long timeout) throws JMSException {
    return textMessage()
            .withCorrelationID(callID)
            .withProperty(PROPERTY_MESSAGE_TYPE, MESSAGE_TYPE_EXTEND_WAIT)
            .withProperty(PROPERTY_REQ_TIMEOUT, timeout)
            .build();
  }

  private Message createErrorSignal(String callID, Throwable error) throws JMSException, IOException {
    return bytesMessage()
            .withCorrelationID(callID)
            .withProperty(PROPERTY_MESSAGE_TYPE, MESSAGE_TYPE_EXCEPTION)
            .withData(JMSUtils.serialize(new ExceptionMessage(callID, error)))
            .build();
  }

  private Message createEOS(String callID) throws JMSException {
    return textMessage()
            .withCorrelationID(callID)
            .withProperty(PROPERTY_MESSAGE_TYPE, MESSAGE_TYPE_STREAM_CLOSED)
            .build();
  }

  private MockMessageBuilder<TextMessage> textMessage() throws JMSException {
    return new MockMessageBuilder<>(TextMessage.class)
            .withProperty(PROTOCOL_VERSION_KEY, ProtocolVersion.V2.getVersionString());
  }

  private MockMessageBuilder<BytesMessage> bytesMessage() throws JMSException {
    return new MockMessageBuilder<>(BytesMessage.class)
            .withProperty(PROTOCOL_VERSION_KEY, ProtocolVersion.V2.getVersionString());
  }


}