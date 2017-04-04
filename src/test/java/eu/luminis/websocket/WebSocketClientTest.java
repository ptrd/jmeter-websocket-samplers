/*
 * Copyright 2016, 2017 Peter Doornbosch
 *
 * This file is part of JMeter-WebSocket-Samplers, a JMeter add-on for load-testing WebSocket applications.
 *
 * JMeter-WebSocket-Samplers is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * JMeter-WebSocket-Samplers is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package eu.luminis.websocket;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import java.io.*;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class WebSocketClientTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testCheckValidServerResponse() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);
        // The test is that it gets here: the server response contains all necessary headers.
    }

    @Test
    public void testCheckInvalidServerSecAcceptHeader() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXB_ZSBub25jZQ==";
        thrown.expect(HttpUpgradeException.class);
        thrown.expectMessage("Server response header 'Sec-WebSocket-Accept' has incorrect value");
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);
    }

    @Test
    public void testCheckMissingServerSecAcceptHeader() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        thrown.expect(HttpUpgradeException.class);
        thrown.expectMessage("Server response should contain 'Sec-WebSocket-Accept' header");
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);
    }

    @Test
    public void testCheckMissingServerUpgradeHeader() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nHost: whatever.com\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXB_ZSBub25jZQ==";
        thrown.expect(HttpUpgradeException.class);
        thrown.expectMessage("Server response should contain 'Upgrade' header with value 'websocket'");
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);
    }

    @Test
    public void testCheckIncorrectServerUpgradeHeader() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nHost: whatever.com\r\nConnection: Upgrade\r\nUpgrade: bullocks\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXB_ZSBub25jZQ==";
        thrown.expect(HttpUpgradeException.class);
        thrown.expectMessage("Server response should contain 'Upgrade' header with value 'websocket'");
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);
    }

    @Test
    public void testCheckMissingServerConnectionHeader() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nHost: whatever.com\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXB_ZSBub25jZQ==";
        thrown.expect(HttpUpgradeException.class);
        thrown.expectMessage("Server response should contain 'Connection' header with value 'Upgrade'");
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);
    }

    @Test
    public void testCheckIncorrectServerConnectionHeader() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nHost: whatever.com\r\nConnection: downgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXB_ZSBub25jZQ==";
        thrown.expect(HttpUpgradeException.class);
        thrown.expectMessage("Server response should contain 'Connection' header with value 'Upgrade'");
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);
    }

    @Test(expected = IllegalStateException.class)
    public void testSendOnClosedConnection() throws IOException {
        new WebSocketClient(new URL("http://nowhere")).sendTextFrame("illegal");
    }

    @Test(expected = IllegalStateException.class)
    public void testDoubleCloseConnection() throws IOException, UnexpectedFrameException {
        WebSocketClient client = new WebSocketClient(new URL("http://nowhere"));
        setPrivateClientState(client, WebSocketClient.WebSocketState.CLOSING);
        client.close(1000, "illegal close", 3000);
    }

    @Test(expected = IllegalStateException.class)
    public void testReceiveOnClosedConnection() throws IOException, UnexpectedFrameException {
        new WebSocketClient(new URL("http://nowhere")).receiveText(3000);
    }

    @Test
    public void testProcessingHttpResponseDoesNotEatFrameBytes() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\nfirstframebytes";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        ByteArrayInputStream bytes = new ByteArrayInputStream(serverResponse.getBytes());
        new WebSocketClient(new URL("http://nowhere")).checkServerResponse(bytes, clientNonce);
        // Check that after processing the HTTP response, all bytes that are not part of the response are still in the stream.
        assertEquals("firstframebytes", new BufferedReader(new InputStreamReader(bytes)).readLine());
    }

    @Test
    public void funnyCasedUpgradeHeaderShouldBeAccepted() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nupgRade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        Map<String, String> headers = new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);

        // Part of the test is that it gets here: when no upgrade header is found, an exception is thrown.
        assertTrue(headers.containsKey("Upgrade"));
    }

    @Test
    public void funnyCasedConnectionHeaderShouldBeAccepted() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConNECtion: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        Map<String, String> headers = new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);

        // Part of the test is that it gets here: when no upgrade header is found, an exception is thrown.
        assertTrue(headers.containsKey("Upgrade"));
    }

    @Test
    public void duplicateHeaderShouldResultInMultipleValue() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nCache-Control: no-cache\r\nCache-Control: no-store\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        Map<String, String> headers = new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);

        // Part of the test is that it gets here: when no upgrade header is found, an exception is thrown.
        assertEquals("no-cache, no-store", headers.get("Cache-Control"));
    }

    @Test
    public void headerValueWithColonShouldNotBeTruncated() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUser-Agent: Mozilla:4.0\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        Map<String, String> headers = new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);

        // Part of the test is that it gets here: when no upgrade header is found, an exception is thrown.
        assertEquals("Mozilla:4.0", headers.get("User-Agent"));
    }

    @Test
    public void httpHeaderWithoutSpacesShouldBeAccepted() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUpgrade:websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        Map<String, String> headers = new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);

        // Part of the test is that it gets here: when no upgrade header is found, an exception is thrown.
        assertEquals("websocket", headers.get("Upgrade"));
    }

    @Test
    public void headerValuesWithSurroundingSpacesShouldBeTrimmed() throws IOException {
        String serverResponse = "HTTP/1.1 101 Switching Protocols\r\nUpgrade:   websocket   \r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String clientNonce = "dGhlIHNhbXBsZSBub25jZQ==";
        Map<String, String> headers = new WebSocketClient(new URL("http://nowhere")).checkServerResponse(new ByteArrayInputStream(serverResponse.getBytes()), clientNonce);

        // Part of the test is that it gets here: when no upgrade header is found, an exception is thrown.
        assertEquals("websocket", headers.get("Upgrade"));
    }

    @Test
    public void urlPathWithoutLeadingSlashShouldBeCorrected() throws MalformedURLException {
        URL url = new URL("https", "nowhere.com", 443, "path");
        assertEquals("/path", new WebSocketClient(url).getConnectUrl().getPath());
    }

    @Test
    public void urlWithEmptyPathShouldBeCorrectedToRoot() throws MalformedURLException {
        URL url = new URL("https", "nowhere.com", 443, "");
        assertEquals("/", new WebSocketClient(url).getConnectUrl().getPath());
    }

    @Test
    public void urlWithWithspacePathShouldBeCorrectedToRoot() throws MalformedURLException {
        URL url = new URL("https", "nowhere.com", 443, "   ");
        assertEquals("/", new WebSocketClient(url).getConnectUrl().getPath());
    }

    @Test
    public void urlPathWithLeadingSpacesShouldBeCorrected() throws MalformedURLException {
        URL url = new URL("https", "nowhere.com", 443, "   path");
        assertEquals("/path", new WebSocketClient(url).getConnectUrl().getPath());
    }

    @Test
    public void clientSuppliedUpgradeHeaderShouldBeIgnored() throws MalformedURLException {
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(1000);

        Map<String, String> headers = new HashMap<>();
        headers.put("Upgrade", "this header should be ignored");
        try {
            createMockWebSocketClientWithOutputBuffer(outputBuffer).connect(headers);
        } catch (IOException e) {
            // Expected, because no response.
        }

        String output = outputBuffer.toString();
        List upgradeHeaders = Arrays.stream(output.split("\r\n")).filter(h -> h.toLowerCase().startsWith("upgrade")).collect(Collectors.toList());
        assertEquals(1, upgradeHeaders.size());
        assertEquals("Upgrade: websocket", upgradeHeaders.get(0));
    }

    @Test
    public void clientSuppliedSecWebSocketKeyHeaderShouldBeIgnored() throws MalformedURLException {
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(1000);

        Map<String, String> headers = new HashMap<>();
        headers.put("SEC-WEBSOCKET-KEY", "this header should be ignored");
        try {
            createMockWebSocketClientWithOutputBuffer(outputBuffer).connect(headers);
        } catch (IOException e) {
            // Expected, because no response.
        }

        String output = outputBuffer.toString();
        List<String> upgradeHeaders = Arrays.stream(output.split("\r\n")).filter(h -> h.toLowerCase().startsWith("sec-websocket-key")).collect(Collectors.toList());
        assertEquals(1, upgradeHeaders.size());
        String base64chars = "A-Za-z0-9+/=";
        assertTrue(Pattern.compile("Sec-WebSocket-Key: [" + base64chars + "]+").matcher(upgradeHeaders.get(0)).matches());
    }

    private void setPrivateClientState(WebSocketClient client, WebSocketClient.WebSocketState newState) {
        Field field = null;
        try {
            field = WebSocketClient.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(client, newState);
        } catch (NoSuchFieldException e) {
            // Impossible
        } catch (IllegalAccessException e) {
            // Impossible
        }
    }

    private WebSocketClient createMockWebSocketClientWithOutputBuffer(ByteArrayOutputStream outputBuffer) throws MalformedURLException {
        return new WebSocketClient(new URL("http", "nowhere.com", 80, "/")) {
            protected Socket createSocket(String host, int port, int connectTimeout, int readTimeout) throws IOException {
                Socket socket = Mockito.mock(Socket.class);
                when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
                when(socket.getOutputStream()).thenReturn(outputBuffer);
                return socket;
            }
        };
    }
}
