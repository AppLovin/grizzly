/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.grizzly.memcached;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.memcached.pool.ObjectPool;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.DataStructures;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Bongjae Chang
 */
public class MemcachedClientFilter extends BaseFilter {

    private static final Logger logger = Grizzly.logger(MemcachedClientFilter.class);

    private static final int MAX_WRITE_BUFFER_SIZE_FOR_OPTIMIZING = 1024 * 1024; // 1m

    private static final int HEADER_LENGTH = 24;
    private static final byte REQUEST_MAGIC_NUMBER = (byte) (0x80 & 0xFF);
    private static final byte RESPONSE_MAGIC_NUMBER = (byte) (0x81 & 0xFF);

    private enum ParsingStatus {
        NONE, READ_HEADER, READ_EXTRAS, READ_KEY, READ_VALUE, DONE, NO_REPLY
    }

    private final Attribute<ParsingStatus> statusAttribute = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("MemcachedClientFilter.Status");
    private final Attribute<MemcachedResponse> responseAttribute = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("MemcachedClientFilter.Response");
    private final Attribute<BlockingQueue<MemcachedRequest>> requestQueueAttribute = Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("MemcachedClientFilter.RequestQueue");

    private final boolean localParsingOptimizing;
    private final boolean onceAllocationOptimizing;

    public MemcachedClientFilter() {
        this(false, true);
    }

    public MemcachedClientFilter(final boolean localParsingOptimizing, final boolean onceAllocationOptimizing) {
        this.localParsingOptimizing = localParsingOptimizing;
        this.onceAllocationOptimizing = onceAllocationOptimizing;
    }

    @Override
    public NextAction handleConnect(FilterChainContext ctx) throws IOException {
        final Connection connection = ctx.getConnection();
        if (connection != null) {
            BlockingQueue<MemcachedRequest> requestQueue = requestQueueAttribute.get(connection);
            if (requestQueue == null) {
                requestQueue = DataStructures.getLTQInstance();
                requestQueueAttribute.set(connection, requestQueue);
            }
        }
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        final Buffer input = ctx.getMessage();
        if (input == null) {
            throw new IOException("input message could not be null");
        }
        if (!input.hasRemaining()) {
            return ctx.getStopAction();
        }
        final Connection connection = ctx.getConnection();
        if (connection == null) {
            throw new IOException("connection could not be null");
        }

        ParsingStatus status = statusAttribute.get(connection);
        if (status == null) {
            status = ParsingStatus.NONE;
            statusAttribute.set(connection, status);
        }

        final BlockingQueue<MemcachedRequest> requestQueue = requestQueueAttribute.get(connection);
        if (requestQueue == null) {
            throw new IOException("request queue must be not null");
        }

        short keyLength;
        byte extraLength;
        int totalBodyLength;
        int valueLength;
        MemcachedRequest sentRequest;
        MemcachedResponse response = responseAttribute.get(connection);
        while (true) {
            if (response == null) {
                response = MemcachedResponse.create();
                responseAttribute.set(connection, response);
            }
            switch (status) {
                case NONE:
                    if (input.remaining() < HEADER_LENGTH) {
                        return ctx.getStopAction(input);
                    }

                    status = ParsingStatus.READ_HEADER;
                    statusAttribute.set(connection, status);
                    break;
                case READ_HEADER:
                    /*
                      |0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|
                      +---------------+---------------+---------------+---------------+
                     0| Magic         | Opcode        | Key Length                    |
                      +---------------+---------------+---------------+---------------+
                     4| Extras length | Data type     | Status                        |
                      +---------------+---------------+---------------+---------------+
                     8| Total body length                                             |
                      +---------------+---------------+---------------+---------------+
                    12| Opaque                                                        |
                      +---------------+---------------+---------------+---------------+
                    16| CAS                                                           |
                      |                                                               |
                      +---------------+---------------+---------------+---------------+
                      Total 24 bytes
                    */
                    input.mark(); // for processing the request again if there is no-reply

                    final byte magic = input.get();
                    if (magic != RESPONSE_MAGIC_NUMBER) {
                        throw new IOException("invalid magic");
                    }
                    final byte op = input.get();
                    sentRequest = requestQueue.peek();
                    if (sentRequest == null) {
                        throw new IOException("invalid response");
                    }
                    final CommandOpcodes commandOpcode = sentRequest.getOp();
                    if (op != commandOpcode.opcode()) {
                        if (sentRequest.isNoReply()) {
                            status = ParsingStatus.NO_REPLY;
                            statusAttribute.set(connection, status);
                            break;
                        } else {
                            throw new IOException("invalid op: " + op);
                        }
                    } else {
                        response.setOp(commandOpcode);
                    }
                    keyLength = input.getShort();
                    if (keyLength < 0) {
                        throw new IOException("invalid key length: " + keyLength);
                    }
                    response.setKeyLength(keyLength);
                    extraLength = input.get();
                    if (extraLength < 0) {
                        throw new IOException("invalid extra length: " + extraLength);
                    }
                    response.setExtraLength(extraLength);
                    response.setDataType(input.get());
                    response.setStatus(ResponseStatus.getResponseStatus(input.getShort()));
                    totalBodyLength = input.getInt();
                    if (totalBodyLength < 0) {
                        throw new IOException("invalid total body length: " + totalBodyLength);
                    }
                    response.setTotalBodyLength(totalBodyLength);
                    final int opaque = input.getInt();
                    if (sentRequest.isNoReply() && opaque != sentRequest.getOpaque()) {
                        status = ParsingStatus.NO_REPLY;
                        statusAttribute.set(connection, status);
                        break;
                    } else {
                        response.setOpaque(opaque);
                    }
                    response.setCas(input.getLong());

                    status = ParsingStatus.READ_EXTRAS;
                    statusAttribute.set(connection, status);
                    break;
                case READ_EXTRAS:
                    extraLength = response.getExtraLength();
                    if (input.remaining() < extraLength) {
                        return ctx.getStopAction(input);
                    }
                    if (extraLength == 4) {
                        response.setFlags(input.getInt());
                    } else {
                        input.position(input.position() + extraLength); // skip
                    }

                    status = ParsingStatus.READ_KEY;
                    statusAttribute.set(connection, status);
                    break;
                case READ_KEY:
                    keyLength = response.getKeyLength();
                    if (input.remaining() < keyLength) {
                        return ctx.getStopAction(input);
                    }
                    if (keyLength > 0) {
                        final int currentPosition = input.position();
                        final int limit = currentPosition + keyLength;
                        response.setDecodedKey(input, currentPosition, limit);
                        input.position(limit);
                    } else {
                        response.setDecodedKey(null);
                    }

                    status = ParsingStatus.READ_VALUE;
                    statusAttribute.set(connection, status);
                    break;
                case READ_VALUE:
                    totalBodyLength = response.getTotalBodyLength();
                    keyLength = response.getKeyLength();
                    extraLength = response.getExtraLength();
                    valueLength = totalBodyLength - keyLength - extraLength;
                    if (valueLength < 0) {
                        throw new IOException("invalid length fields: "
                                + "total body length=" + totalBodyLength
                                + ", key length = " + keyLength
                                + ", extra length = " + extraLength);
                    }
                    if (input.remaining() < valueLength) {
                        return ctx.getStopAction(input);
                    }

                    final int currentPosition = input.position();
                    final int limit = currentPosition + valueLength;
                    if (response.getStatus() == ResponseStatus.No_Error) {
                        if (valueLength > 0) {
                            sentRequest = requestQueue.peek();
                            if (sentRequest == null) {
                                throw new IOException("invalid response");
                            }
                            response.setDecodedValue(input, currentPosition, limit);
                            input.position(limit);
                        } else {
                            response.setDecodedValue(null);
                        }
                    } else {
                        response.setDecodedValue(null);
                        input.position(limit);
                    }

                    status = ParsingStatus.DONE;
                    statusAttribute.set(connection, status);
                    break;
                case DONE:
                    final boolean complete = response.complete();
                    if (complete) {
                        sentRequest = requestQueue.remove();
                        response.setResult(sentRequest.getOriginKey());
                        if (sentRequest.disposed.compareAndSet(false, true) && !sentRequest.isNoReply()) {
                            sentRequest.response = response.getResult();
                            sentRequest.responseStatus = response.getStatus();
                            sentRequest.notify.countDown();
                            response.recycle();
                            response = null;
                            responseAttribute.remove(connection);
                        }
                    } else {
                        sentRequest = requestQueue.peek();
                        response.setResult(sentRequest.getOriginKey());
                        if (!sentRequest.disposed.get() && !sentRequest.isNoReply()) {
                            sentRequest.response = response.getResult();
                            sentRequest.responseStatus = response.getStatus();
                            sentRequest.notify.countDown();
                            response.recycle();
                            response = null;
                            responseAttribute.remove(connection);
                        }
                    }

                    if (localParsingOptimizing) {
                        if (input.remaining() > 0) {
                            status = ParsingStatus.NONE;
                            statusAttribute.set(connection, status);
                            break;
                        } else {
                            input.tryDispose();
                            statusAttribute.remove(connection);
                            return ctx.getStopAction();
                        }
                    } else {
                        // Check if the input buffer has more than 1 complete memcached message
                        // If yes - split up the first message and the remainder
                        final Buffer remainder = input.remaining() > 0 ? input.split(input.position()) : null;
                        input.tryDispose();
                        statusAttribute.remove(connection);
                        if (remainder == null) {
                            return ctx.getStopAction();
                        } else {
                            // Instruct FilterChain to store the remainder (if any) and continue execution
                            return ctx.getInvokeAction(remainder);
                        }
                    }
                case NO_REPLY:
                    // processing next internal memcached request
                    sentRequest = requestQueue.remove();
                    sentRequest.response = Boolean.TRUE;
                    sentRequest.responseStatus = ResponseStatus.No_Error;
                    sentRequest.notify.countDown();
                    input.reset();

                    status = ParsingStatus.READ_HEADER;
                    statusAttribute.set(connection, status);
                    break;
                default:
                    throw new IllegalStateException("invalid internal status");
            }
        }
    }

    @Override
    public NextAction handleWrite(FilterChainContext ctx) throws IOException {
        final MemcachedRequest[] requests = ctx.getMessage();
        if (requests == null) {
            throw new IOException("Input message could not be null");
        }
        final Connection connection = ctx.getConnection();
        if (connection == null) {
            throw new IOException("connection must be not null. this connection was already closed or not opened");
        }

        final BlockingQueue<MemcachedRequest> requestQueue = requestQueueAttribute.get(connection);
        if (requestQueue == null) {
            throw new IOException("request queue must be not null. this connection was already closed or not opened. connection=" + connection);
        }
        MemoryManager memoryManager = ctx.getMemoryManager();
        if (memoryManager == null) {
            memoryManager = MemoryManager.DEFAULT_MEMORY_MANAGER;
        }

        final Buffer resultBuffer;

        if (onceAllocationOptimizing) {
            final int totalSize = calculateTotalPacketSize(requests);
            if (totalSize <= MAX_WRITE_BUFFER_SIZE_FOR_OPTIMIZING) {
                resultBuffer = makePacketsByOnceAllocation(memoryManager, connection, requests, requestQueue, totalSize);
            } else {
                resultBuffer = makePackets(memoryManager, connection, requests, requestQueue);
            }
        } else {
            resultBuffer = makePackets(memoryManager, connection, requests, requestQueue);
        }
        if (resultBuffer != null) {
            resultBuffer.allowBufferDispose(true);
            ctx.setMessage(resultBuffer);
        }
        return ctx.getInvokeAction();
    }

    private int calculateTotalPacketSize(final MemcachedRequest[] requests) {
        if (requests == null) {
            return 0;
        }
        int totalSize = requests.length * HEADER_LENGTH;
        for (MemcachedRequest request : requests) {
            totalSize += request.getExtrasLength();
            totalSize += request.getKeyLength();
            totalSize += request.getValueLength();
        }
        return totalSize;
    }

    private Buffer makePacketsByOnceAllocation(final MemoryManager memoryManager,
                                               final Connection connection,
                                               final MemcachedRequest[] requests,
                                               final BlockingQueue<MemcachedRequest> requestQueue,
                                               final int totalSize) throws IOException {
        if (memoryManager == null) {
            throw new IllegalArgumentException("memory manager must not be null");
        }
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        if (requestQueue == null) {
            throw new IllegalArgumentException("request queue must be not null");
        }
        if (totalSize < HEADER_LENGTH) {
            throw new IllegalArgumentException("invalid packet size");
        }

        final Buffer buffer = memoryManager.allocate(totalSize);
        for (MemcachedRequest request : requests) {
            // header
            final byte extrasLength = request.getExtrasLength();
            buffer.put(REQUEST_MAGIC_NUMBER);
            buffer.put(request.getOp().opcode());
            final short keyLength = request.getKeyLength();
            buffer.putShort(keyLength);
            buffer.put(extrasLength);
            buffer.put(request.getDataType());
            buffer.putShort(request.getvBucketId());
            final int totalLength = keyLength + request.getValueLength() + extrasLength;
            buffer.putInt(totalLength);
            buffer.putInt(request.getOpaque());
            buffer.putLong(request.getCas());

            // extras
            request.fillExtras(buffer);

            // key
            final Buffer keyBuffer = request.getKey();
            if (request.hasKey() && keyBuffer != null) {
                buffer.put(keyBuffer);
                keyBuffer.tryDispose();
            }

            // value
            final Buffer valueBuffer = request.getValue();
            if (request.hasValue() && valueBuffer != null) {
                buffer.put(valueBuffer);
                valueBuffer.tryDispose();
            }
            // store request
            try {
                requestQueue.put(request);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("failed to put the request", ie);
            }
        }
        buffer.flip();
        return buffer;
    }

    private Buffer makePackets(final MemoryManager memoryManager,
                               final Connection connection,
                               final MemcachedRequest[] requests,
                               final BlockingQueue<MemcachedRequest> requestQueue) throws IOException {
        if (memoryManager == null) {
            throw new IllegalArgumentException("memory manager must not be null");
        }
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        if (requests == null) {
            throw new IllegalArgumentException("requests must not be null");
        }
        if (requestQueue == null) {
            throw new IllegalArgumentException("request queue must be not null");
        }
        Buffer resultBuffer = null;
        for (MemcachedRequest request : requests) {
            // header
            final byte extrasLength = request.getExtrasLength();
            final Buffer buffer = memoryManager.allocate(HEADER_LENGTH + extrasLength);
            buffer.put(REQUEST_MAGIC_NUMBER);
            buffer.put(request.getOp().opcode());

            final short keyLength = request.getKeyLength();
            buffer.putShort(keyLength);
            buffer.put(extrasLength);
            buffer.put(request.getDataType());
            buffer.putShort(request.getvBucketId());
            final int totalLength = keyLength + request.getValueLength() + extrasLength;
            buffer.putInt(totalLength);
            buffer.putInt(request.getOpaque());
            buffer.putLong(request.getCas());

            // extras
            request.fillExtras(buffer);

            buffer.flip();
            buffer.allowBufferDispose(true);
            if (resultBuffer == null) {
                resultBuffer = buffer;
            } else {
                resultBuffer = Buffers.appendBuffers(memoryManager, resultBuffer, buffer);
            }

            // key
            final Buffer keyBuffer = request.getKey();
            if (request.hasKey() && keyBuffer != null) {
                keyBuffer.allowBufferDispose(true);
                resultBuffer = Buffers.appendBuffers(memoryManager, resultBuffer, keyBuffer);
            }

            // value
            final Buffer valueBuffer = request.getValue();
            if (request.hasValue() && valueBuffer != null) {
                valueBuffer.allowBufferDispose(true);
                resultBuffer = Buffers.appendBuffers(memoryManager, resultBuffer, valueBuffer);
            }

            // store request
            try {
                requestQueue.put(request);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("failed to put the request", ie);
            }
        }
        return resultBuffer;
    }

    @SuppressWarnings("unchecked")
    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {
        final Connection connection = ctx.getConnection();
        if (connection != null) {
            final BlockingQueue<MemcachedRequest> requestQueue = requestQueueAttribute.get(connection);
            if (requestQueue != null) {
                requestQueue.clear();
                requestQueueAttribute.remove(connection);
            }
            MemcachedResponse response = responseAttribute.remove(connection);
            if (response != null) {
                response.recycle();
            }
            statusAttribute.remove(connection);

            final AttributeHolder attributeHolder = connection.getAttributes();
            if (attributeHolder != null) {
                final Object attribute = attributeHolder.getAttribute(GrizzlyMemcachedCache.CONNECTION_POOL_ATTRIBUTE_NAME);
                if (attribute instanceof ObjectPool) {
                    final ObjectPool connectionPool = (ObjectPool) attribute;
                    try {
                        connectionPool.removeObject(connection.getPeerAddress(), connection);
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return ctx.getInvokeAction();
    }

    @SuppressWarnings("unchecked")
    public <K, V> Map<K, V> getMultiResponse(final Connection connection,
                                             final MemcachedRequest[] requests,
                                             final long timeoutInMillis,
                                             final Map<K, V> result) throws InterruptedException, TimeoutException {
        if (connection == null) {
            throw new IllegalArgumentException("connection must be not null");
        }
        if (requests == null) {
            throw new IllegalArgumentException("requests must be not null");
        }
        final int requestLen = requests.length;
        if (requestLen < 1) {
            throw new IllegalArgumentException("requests must include at least one request");
        }
        if (result == null) {
            throw new IllegalArgumentException("result must be not null");
        }

        Object response;
        ResponseStatus responseStatus;
        final int lastIndex = requestLen - 1;
        // wait for receiving last packet
        if (timeoutInMillis < 0) {
            requests[lastIndex].notify.await();
            response = requests[lastIndex].response;
            responseStatus = requests[lastIndex].responseStatus;
        } else {
            requests[lastIndex].notify.await(timeoutInMillis, TimeUnit.MILLISECONDS);
            response = requests[lastIndex].response;
            responseStatus = requests[lastIndex].responseStatus;
        }
        if (response == null) {
            throw new TimeoutException("timed out while getting the response");
        }
        if (!ResponseStatus.isError(responseStatus)) {
            result.put((K) requests[lastIndex].getOriginKey(), (V) response);
        }
        // collect previous packets
        for (int i = 0; i < requestLen - 1; i++) {
            response = requests[i].response;
            responseStatus = requests[i].responseStatus;
            if (response != null) {
                if (!ResponseStatus.isError(responseStatus)) {
                    result.put((K) requests[i].getOriginKey(), (V) response);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public <V> V getCorrelatedResponse(final Connection connection,
                                       final MemcachedRequest request,
                                       final long timeoutInMillis) throws InterruptedException, TimeoutException {
        if (connection == null) {
            throw new IllegalArgumentException("connection must be not null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must be not null");
        }
        if (request.isNoReply()) {
            throw new IllegalArgumentException("request type is no reply");
        }

        Object response;
        ResponseStatus responseStatus;
        if (timeoutInMillis < 0) {
            request.notify.await();
            response = request.response;
            responseStatus = request.responseStatus;
        } else {
            request.notify.await(timeoutInMillis, TimeUnit.MILLISECONDS);
            response = request.response;
            responseStatus = request.responseStatus;
        }

        if (response == null && responseStatus == null) {
            throw new TimeoutException("timed out while getting the response");
        }
        final V result;
        if (!ResponseStatus.isError(responseStatus)) {
            result = (V) response;
        } else {
            result = null;
            if (logger.isLoggable(Level.SEVERE)) {
                logger.log(Level.SEVERE, "error status code={0}, status msg={1}, op={2}, key={3}", new Object[]{responseStatus, responseStatus.message(), request.getOp(), request.getOriginKey()});
            }
        }
        return result;
    }
}
