/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.coyote.http11;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

import org.apache.coyote.ActionCode;
import org.apache.coyote.OutputBuffer;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * Output buffer.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalOutputBuffer extends AbstractOutputBuffer<Socket>
    implements ByteChunk.ByteOutputChannel {

    // ----------------------------------------------------------- Constructors

    /**
     * Default constructor.
     */
    public InternalOutputBuffer(Response response, int headerBufferSize) {

        this.response = response;

        buf = new byte[headerBufferSize];

        outputStreamOutputBuffer = new OutputStreamOutputBuffer();

        filterLibrary = new OutputFilter[0];
        activeFilters = new OutputFilter[0];
        lastActiveFilter = -1;

        socketBuffer = new ByteChunk();
        socketBuffer.setByteOutputChannel(this);

        committed = false;
        finished = false;

    }

    /**
     * Underlying output stream. Note: protected to assist with unit testing
     */
    protected OutputStream outputStream;


    /**
     * Socket buffer.
     */
    private ByteChunk socketBuffer;


    /**
     * Socket buffer (extra buffering to reduce number of packets sent).
     */
    private boolean useSocketBuffer = false;


    /**
     * Set the socket buffer size.
     */
    public void setSocketBuffer(int socketBufferSize) {

        if (socketBufferSize > 500) {
            useSocketBuffer = true;
            socketBuffer.allocate(socketBufferSize, socketBufferSize);
        } else {
            useSocketBuffer = false;
        }

    }


    // --------------------------------------------------------- Public Methods

    @Override
    public void init(SocketWrapper<Socket> socketWrapper,
            AbstractEndpoint<Socket> endpoint) throws IOException {

        outputStream = socketWrapper.getSocket().getOutputStream();
    }


    /**
     * Flush the response.
     *
     * @throws IOException an underlying I/O error occurred
     */
    @Override
    public void flush()
        throws IOException {

        //判断有没有发送请求头
        //AbstractOutputBuffer.flush
        super.flush();

        // Flush the current buffer
        // 上面的流程目的是把数据发送给socket，但是如果使用了socketBuffer，那么就只会把数据发送给socketbuffer，所以这里要调用socketbuffer最终发送数据
        if (useSocketBuffer) {
            socketBuffer.flushBuffer();
        }

    }


    /**
     * Recycle the output buffer. This should be called when closing the
     * connection.
     */
    @Override
    public void recycle() {
        super.recycle();
        outputStream = null;
    }


    /**
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     */
    @Override
    public void nextRequest() {
        super.nextRequest();
        socketBuffer.recycle();
    }


    /**
     * End request.
     *
     * @throws IOException an underlying I/O error occurred
     */
    @Override
    public void endRequest()
        throws IOException {

        super.endRequest(); // 先调用父类去发送请求头 OutputFileter.end()

        // 如果使用了socketbuffer，则将socketbuffer中的数据发送出去
        if (useSocketBuffer) {

            socketBuffer.flushBuffer();
        }
    }


    // ------------------------------------------------ HTTP/1.1 Output Methods


    /**
     * Send an acknowledgment.
     */
    @Override
    public void sendAck()
        throws IOException {

        if (!committed)
            outputStream.write(Constants.ACK_BYTES);

    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Commit the response.
     *
     * @throws IOException an underlying I/O error occurred
     */
    @Override
    protected void commit()
        throws IOException {

        // The response is now committed
        committed = true;
        response.setCommitted(true);

        if (pos > 0) {
            // Sending the response header buffer，如果用了socketbuffer则写写到socketbuffer中，如果没有则直接通过socketoutputstream返回
            if (useSocketBuffer) {
                socketBuffer.append(buf, 0, pos);
            } else {
                //SocketOutputStream
                outputStream.write(buf, 0, pos);
            }
        }

    }


    /**
     * Callback to write data from the buffer.
     */
    @Override
    public void realWriteBytes(byte cbuf[], int off, int len)
        throws IOException {
        if (len > 0) {
            outputStream.write(cbuf, off, len);
        }
    }


    // ----------------------------------- OutputStreamOutputBuffer Inner Class


    /**
     * This class is an output buffer which will write data to an output
     * stream.
     */
    protected class OutputStreamOutputBuffer implements OutputBuffer {


        /**
         * Write chunk.
         */
        @Override
        public int doWrite(ByteChunk chunk, Response res) throws IOException {
            try {
                int length = chunk.getLength();
                // 如果再次发送到缓冲区中，则该缓冲区（ByteChunk的buff属性）满了之后就会发送，或者当前请求要结束时发送
                if (useSocketBuffer) {
                    socketBuffer.append(chunk.getBuffer(), chunk.getStart(),
                                        length);
                } else {
                    outputStream.write(chunk.getBuffer(), chunk.getStart(),
                                       length);
                }
                byteCount += chunk.getLength();
                return chunk.getLength();
            } catch (IOException ioe) {
                response.action(ActionCode.CLOSE_NOW, ioe);
                // Re-throw
                throw ioe;
            }
        }

        @Override
        public long getBytesWritten() {
            return byteCount;
        }
    }
}
