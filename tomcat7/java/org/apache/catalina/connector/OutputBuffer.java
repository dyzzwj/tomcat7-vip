/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.connector;


import java.io.IOException;
import java.io.Writer;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Globals;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.C2BConverter;
import org.apache.tomcat.util.buf.CharChunk;


/**
 * The buffer used by Tomcat response. This is a derivative of the Tomcat 3.3
 * OutputBuffer, with the removal of some of the state handling (which in
 * Coyote is mostly the Processor's responsibility).
 *
 * @author Costin Manolache
 * @author Remy Maucherat
 */
public class OutputBuffer extends Writer
    implements ByteChunk.ByteOutputChannel, CharChunk.CharOutputChannel {


    // -------------------------------------------------------------- Constants


    public static final String DEFAULT_ENCODING =
        org.apache.coyote.Constants.DEFAULT_CHARACTER_ENCODING;
    public static final int DEFAULT_BUFFER_SIZE = 8*1024;


    // ----------------------------------------------------- Instance Variables


    /**
     * The byte buffer.
     */
    private final ByteChunk bb;


    /**
     * The chunk buffer.
     */
    private final CharChunk cb;


    /**
     * State of the output buffer.
     */
    private boolean initial = true;


    /**
     * Number of bytes written.
     */
    private long bytesWritten = 0;


    /**
     * Number of chars written.
     */
    private long charsWritten = 0;


    /**
     * Flag which indicates if the output buffer is closed.
     */
    private boolean closed = false;


    /**
     * Do a flush on the next operation.
     */
    private boolean doFlush = false;


    /**
     * Byte chunk used to output bytes.
     */
    private final ByteChunk outputChunk = new ByteChunk();


    /**
     * Char chunk used to output chars.
     */
    private CharChunk outputCharChunk = new CharChunk();


    /**
     * Encoding to use.
     */
    private String enc;


    /**
     * Encoder is set.
     */
    private boolean gotEnc = false;


    /**
     * List of encoders.
     */
    protected final ConcurrentHashMap<String, C2BConverter> encoders =
            new ConcurrentHashMap<String, C2BConverter>();


    /**
     * Current char to byte converter.
     */
    protected C2BConverter conv;


    /**
     * Associated Coyote response.
     */
    private Response coyoteResponse;


    /**
     * Suspended flag. All output bytes will be swallowed if this is true.
     */
    private boolean suspended = false;


    // ----------------------------------------------------------- Constructors


    /**
     * Default constructor. Allocate the buffer with the default buffer size.
     */
    public OutputBuffer() {

        this(DEFAULT_BUFFER_SIZE);

    }


    /**
     * Alternate constructor which allows specifying the initial buffer size.
     *
     * @param size Buffer size to use
     */
    public OutputBuffer(int size) {

        bb = new ByteChunk(size);
        bb.setLimit(size);
        bb.setByteOutputChannel(this);
        cb = new CharChunk(size);
        cb.setLimit(size);
        cb.setOptimizedWrite(false);
        cb.setCharOutputChannel(this);

    }


    // ------------------------------------------------------------- Properties


    /**
     * Associated Coyote response.
     *
     * @param coyoteResponse Associated Coyote response
     */
    public void setResponse(Response coyoteResponse) {
        this.coyoteResponse = coyoteResponse;
    }


    /**
     * Get associated Coyote response.
     *
     * @return the associated Coyote response
     */
    @Deprecated
    public Response getResponse() {
        return this.coyoteResponse;
    }


    /**
     * Is the response output suspended ?
     *
     * @return suspended flag value
     */
    public boolean isSuspended() {
        return this.suspended;
    }


    /**
     * Set the suspended flag.
     *
     * @param suspended New suspended flag value
     */
    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }


    /**
     * Is the response output closed ?
     *
     * @return closed flag value
     */
    public boolean isClosed() {
        return this.closed;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Recycle the output buffer.
     */
    public void recycle() {

        initial = true;
        bytesWritten = 0;
        charsWritten = 0;

        bb.recycle();
        cb.recycle();
        outputCharChunk.setChars(null, 0, 0);
        closed = false;
        suspended = false;
        doFlush = false;

        if (conv!= null) {
            conv.recycle();
        }

        gotEnc = false;
        enc = null;

    }


    /**
     * Clear cached encoders (to save memory for Comet requests).
     */
    public void clearEncoders() {
        encoders.clear();
    }


    /**
     * Close the output buffer. This tries to calculate the response size if
     * the response has not been committed yet.
     *
     * @throws IOException An underlying IOException occurred
     */
    @Override
    public void  close()
        throws IOException {

        if (closed) {
            return;
        }
        if (suspended) {
            return;
        }

        // If there are chars, flush all of them to the byte buffer now as bytes are used to
        // calculate the content-length (if everything fits into the byte buffer, of course).
        if (cb.getLength() > 0) {
            cb.flushBuffer();
        }

        /**
         *   - 如果没有发送过数据到socket的send buff（即响应体数据都被上层缓冲区和底层缓冲区 缓存了并且没有flush()），则设置contentlength
         *   - 如果之前发送过数据到socket的send buff（1、发送响应体较大 上层缓冲和底层缓冲装不下 2、只要调用了flush），
         *   那么响应头中就不会有contentlength了，这种情况数据是分块发送的了
         */
        //
        if ((!coyoteResponse.isCommitted()) && (coyoteResponse.getContentLengthLong() == -1) &&
                !coyoteResponse.getRequest().method().equals("HEAD")) {
            // If this didn't cause a commit of the response, the final content
            // length can be calculated. Only do this if this is not a HEAD
            // request since in that case no body should have been written and
            // setting a value of zero here will result in an explicit content
            // length of zero being set on the response.
            if (!coyoteResponse.isCommitted()) {
                coyoteResponse.setContentLength(bb.getLength());
            }
        }

        if (coyoteResponse.getStatus() ==
                HttpServletResponse.SC_SWITCHING_PROTOCOLS) {
            doFlush(true);
        } else {
            /**
             *  realFlush为false, 所以并不会真正把数据发送给socket, 而只是把上层缓冲区的数据发送给下层缓冲区
             *  (如果没有启用下层缓冲区，则直接发送到socket sendbuff)
             */
            doFlush(false);
        }
        closed = true;

        // The request should have been completely read by the time the response
        // is closed. Further reads of the input a) are pointless and b) really
        // confuse AJP (bug 50189) so close the input buffer to prevent them.
        Request req = (Request) coyoteResponse.getRequest().getNote(
                CoyoteAdapter.ADAPTER_NOTES);
        req.inputBuffer.close();

        // 把下层的缓冲区中的内容发送给socket
        coyoteResponse.finish();

    }


    /**
     * Flush bytes or chars contained in the buffer.
     *
     * @throws IOException An underlying IOException occurred
     */
    @Override
    public void flush() throws IOException {
        doFlush(true);
    }


    /**
     * Flush bytes or chars contained in the buffer.
     *
     * @throws IOException An underlying IOException occurred
     */
    protected void doFlush(boolean realFlush) throws IOException {

        if (suspended) {
            return;
        }

        try {
            doFlush = true;
            if (initial) {
                // 先发送请求头，再发送请求体
                /**
                 *  构造响应头并把响应头的数据写入到InternalOutputBuffer中
                 *  决定使用contentLength和chunked分块传输
                 */
                coyoteResponse.sendHeaders();
                initial = false;
            }
            if (cb.getLength() > 0) {
                cb.flushBuffer();
            }
            if (bb.getLength() > 0) {
                // 这里只是把上层缓冲区中的数据发送到底层缓冲区中，所以数据到底会不会发送给socket并不确定
                bb.flushBuffer();
            }
        } finally {
            doFlush = false;
        }

        if (realFlush) {
            // 如果是真正flush，把底层缓冲区中的数据发送给socket
            coyoteResponse.action(ActionCode.CLIENT_FLUSH, null);
            // If some exception occurred earlier, or if some IOE occurred
            // here, notify the servlet with an IOE
            if (coyoteResponse.isExceptionPresent()) {
                throw new ClientAbortException(coyoteResponse.getErrorException());
            }
        }

    }


    // ------------------------------------------------- Bytes Handling Methods

    /**
     * Sends the buffer data to the client output, checking the
     * state of Response and calling the right interceptors.
     *
     * @param buf Byte buffer to be written to the response
     * @param off Offset
     * @param cnt Length
     *
     * @throws IOException An underlying IOException occurred
     */
    @Override
    public void realWriteBytes(byte buf[], int off, int cnt)
            throws IOException {


        if (closed) {
            return;
        }
        if (coyoteResponse == null) {
            return;
        }

        // If we really have something to write
        if (cnt > 0) {
            // real write to the adapter
            outputChunk.setBytes(buf, off, cnt);
            try {
                coyoteResponse.doWrite(outputChunk);
            } catch (IOException e) {
                // An IOException on a write is almost always due to
                // the remote client aborting the request.  Wrap this
                // so that it can be handled better by the error dispatcher.
                throw new ClientAbortException(e);
            }
        }

    }


    public void write(byte b[], int off, int len) throws IOException {

        if (suspended) {
            return;
        }

        writeBytes(b, off, len);

    }


    private void writeBytes(byte b[], int off, int len)
        throws IOException {

        if (closed) {
            return;
        }

        // 将数据先写入ByteChunk的缓冲区中, 如果缓冲区满了，直接把数据写入到socketBuffer
        //ByteChunk
        bb.append(b, off, len);
        bytesWritten += len;

        // if called from within flush(), then immediately flush
        // remaining bytes
        // 如果此前已经调用过flush方法
        if (doFlush) {
            // 那么每次write都把缓冲中的数据发送出去
            bb.flushBuffer();
        }

    }


    public void writeByte(int b)
        throws IOException {

        if (suspended) {
            return;
        }

        bb.append((byte) b);
        bytesWritten++;

    }


    // ------------------------------------------------- Chars Handling Methods


    /**
     * Convert the chars to bytes, then send the data to the client.
     *
     * @param buf Char buffer to be written to the response
     * @param off Offset
     * @param len Length
     *
     * @throws IOException An underlying IOException occurred
     */
    @Override
    public void realWriteChars(char buf[], int off, int len)
        throws IOException {

        outputCharChunk.setChars(buf, off, len);
        while (outputCharChunk.getLength() > 0) {
            conv.convert(outputCharChunk, bb);
            if (bb.getLength() == 0) {
                // Break out of the loop if more chars are needed to produce any output
                break;
            }
            if (outputCharChunk.getLength() > 0) {
                if (bb.getBuffer().length == bb.getEnd() && bb.getLength() < bb.getLimit()) {
                    // Need to expand output buffer
                    bb.makeSpace(outputCharChunk.getLength());
                } else {
                    bb.flushBuffer();
                }
            } else if (conv.isUndeflow() && bb.getLength() - 4 < bb.getLimit()) {
                // Handle an edge case. There are no more chars to write at the
                // moment but there is a leftover character in the converter
                // which must be part of a surrogate pair. The byte buffer does
                // not have enough space left to output the bytes for this pair
                // once it is complete )it will require 4 bytes) so flush now to
                // prevent the bytes for the leftover char and the rest of the
                // surrogate pair yet to be written from being lost.
                // See TestOutputBuffer#testUtf8SurrogateBody()
                bb.flushBuffer();
            }
        }

    }

    @Override
    public void write(int c)
        throws IOException {

        if (suspended) {
            return;
        }

        cb.append((char) c);
        charsWritten++;

    }


    @Override
    public void write(char c[])
        throws IOException {

        if (suspended) {
            return;
        }

        write(c, 0, c.length);

    }


    @Override
    public void write(char c[], int off, int len)
        throws IOException {

        if (suspended) {
            return;
        }

        cb.append(c, off, len);
        charsWritten += len;

    }


    /**
     * Append a string to the buffer
     */
    @Override
    public void write(String s, int off, int len)
        throws IOException {

        if (suspended) {
            return;
        }

        charsWritten += len;
        if (s == null) {
            s = "null";
        }
        cb.append(s, off, len);
        charsWritten += len;
    }


    @Override
    public void write(String s)
        throws IOException {

        if (suspended) {
            return;
        }

        if (s == null) {
            s = "null";
        }
        cb.append(s);
        charsWritten += s.length();
    }


    public void setEncoding(String s) {
        enc = s;
    }


    public void checkConverter()
        throws IOException {

        if (!gotEnc) {
            setConverter();
        }

    }


    protected void setConverter()
        throws IOException {

        if (coyoteResponse != null) {
            enc = coyoteResponse.getCharacterEncoding();
        }

        gotEnc = true;
        if (enc == null) {
            enc = DEFAULT_ENCODING;
        }
        conv = encoders.get(enc);
        if (conv == null) {
            if (Globals.IS_SECURITY_ENABLED){
                try{
                    conv = AccessController.doPrivileged(
                            new PrivilegedExceptionAction<C2BConverter>(){

                                @Override
                                public C2BConverter run() throws IOException{
                                    return new C2BConverter(enc);
                                }

                            }
                    );
                } catch (PrivilegedActionException ex){
                    Exception e = ex.getException();
                    if (e instanceof IOException) {
                        throw (IOException)e;
                    } else {
                        throw new IOException(ex);
                    }
                }
            } else {
                conv = new C2BConverter(enc);
            }

            encoders.put(enc, conv);

        }
    }


    // --------------------  BufferedOutputStream compatibility


    public long getContentWritten() {
        return bytesWritten + charsWritten;
    }

    /**
     * True if this buffer hasn't been used ( since recycle() ) -
     * i.e. no chars or bytes have been added to the buffer.
     */
    public boolean isNew() {
        return (bytesWritten == 0) && (charsWritten == 0);
    }


    public void setBufferSize(int size) {
        if (size > bb.getLimit()) {// ??????
            bb.setLimit(size);
        }
    }


    public void reset() {
        reset(false);
    }

    public void reset(boolean resetWriterStreamFlags) {
        bb.recycle();
        cb.recycle();
        bytesWritten = 0;
        charsWritten = 0;
        if (resetWriterStreamFlags) {
            gotEnc = false;
            enc = null;
        }
        initial = true;
    }


    public int getBufferSize() {
        return bb.getLimit();
    }


}
