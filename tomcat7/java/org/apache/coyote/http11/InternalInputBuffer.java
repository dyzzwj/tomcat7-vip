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

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.parser.HttpParser;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketWrapper;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Implementation of InputBuffer which provides HTTP request header parsing as
 * well as transfer decoding.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalInputBuffer extends AbstractInputBuffer<Socket> {

    private static final Log log = LogFactory.getLog(InternalInputBuffer.class);


    /**
     * Underlying input stream.
     */
    private InputStream inputStream;


    /**
     * Default constructor.
     */
    public InternalInputBuffer(Request request, int headerBufferSize,
            boolean rejectIllegalHeaderName, HttpParser httpParser) {

        this.request = request;
        headers = request.getMimeHeaders();

        // 请求头的缓冲区域大小，一个请求的请求头数据不能超过这个区域，默认为8192，也就是8*1024个字节=8kb
        buf = new byte[headerBufferSize];

        this.rejectIllegalHeaderName = rejectIllegalHeaderName;
        this.httpParser = httpParser;

        inputStreamInputBuffer = new InputStreamInputBuffer();

        filterLibrary = new InputFilter[0];
        activeFilters = new InputFilter[0];
        lastActiveFilter = -1;

        parsingHeader = true;
        swallowInput = true;

    }


    /**
     * Read the request line. This function is meant to be used during the
     * HTTP request header parsing. Do NOT attempt to read the request body
     * using it.
     *
     * @throws IOException If an exception occurs during the underlying socket
     * read operations, or if the given buffer is not big enough to accommodate
     * the whole line.
     */

    /**
     * 解析请求行   GET /v1/internal/user HTTP/1.1
     */
    @Override
    public boolean parseRequestLine(boolean useAvailableDataOnly)

        throws IOException {

        int start = 0;

        //
        // Skipping blank lines
        //

        byte chr = 0;
        /**
         * 过滤GET之前的回车换行符
         */
        do {
            // 把buf里面的字符一个个取出来进行判断，遇到非回车换行符则会退出

            // Read new bytes if needed
            /**
             *  pos = lastValid buf中的数据读完了
             *  pos > lastValid 异常
             */
            // 如果一直读到的回车换行符则再次调用fill,从inputStream里面读取数据填充到buf中
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            // Set the start time once we start reading data (even if it is
            // just skipping blank lines)
            if (request.getStartTime() < 0) {
                request.setStartTime(System.currentTimeMillis());
            }
            chr = buf[pos++];
        } while ((chr == Constants.CR) || (chr == Constants.LF));

        /**
         * 上面的buf[pos++]中pos是先用后加 所以这里--
         */
        pos--;

        // Mark the current buffer position
        start = pos;

        //
        // Reading the method name
        // Method name is a token
        //

        boolean space = false;


        /**
         * 找到空格或tab 说明找到GET了 (Get | Post ..)  退出循环
         */
        while (!space) {

            // Read new bytes if needed
            /**
             *  pos = lastValid buf中的数据读完了
             *  pos > lastValid 异常
             */
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            // Spec says method name is a token followed by a single SP but
            // also be tolerant of multiple SP and/or HT.
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                space = true;
                //设置字节  没有转换成String？ 1、效率（开发人员不一定需要用） 2、不用考虑编码
                /**
                 *  设置请求方法（以字节的形式保存 并没有转换成String）
                 *  ByteChunk:字节块
                 *  buf：源字节数组
                 *  start:buf的下标为start表示ByteChunk中数据的开始位置
                 *  end:buf的下标为end表示ByteChunk中数据的结束位置
                 *
                 *  注意 并没有把buf中的数据拷贝到ByteChunk 而是ByteChunk保存了InputBuffer中buf的引用,
                 *  并使用start、end记录当前ByteChunk的数据在buf中的起始集合结束为止
                 *  在真正调用RequestFacade.getMethod()方法时 才会把 coyote.Request.method()的字节数据转换为String
                 *  即取InputBuffer中buf下标为start和end的部分 转为String
                 */
                request.method().setBytes(buf, start, pos - start);
            } else if (!HttpParser.isToken(buf[pos])) {
                throw new IllegalArgumentException(sm.getString("iib.invalidmethod"));
            }

            pos++;

        }

        // Spec says single SP but also be tolerant of multiple SP and/or HT
        /**
         * 过滤空格或tab
         */
        while (space) {
            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                pos++;
            } else {
                space = false;
            }
        }

        // Mark the current buffer position
        start = pos;
        int end = 0;
        int questionPos = -1;

        //
        // Reading the URI
        //

        boolean eol = false;

        /**
         * 解析uri /v1/internal/user
         */
        while (!space) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            // Spec says single SP but it also says be tolerant of HT
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                space = true;
                end = pos;
            } else if ((buf[pos] == Constants.CR)
                       || (buf[pos] == Constants.LF)) {
                // HTTP/0.9 style request
                eol = true;
                space = true;
                end = pos;
            } else if ((buf[pos] == Constants.QUESTION) && (questionPos == -1)) {
                questionPos = pos;
            } else if (questionPos != -1 && !httpParser.isQueryRelaxed(buf[pos])) {
                // %nn decoding will be checked at the point of decoding
                throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget"));
            } else if (httpParser.isNotRequestTargetRelaxed(buf[pos])) {
                // This is a general check that aims to catch problems early
                // Detailed checking of each part of the request target will
                // happen in AbstractHttp11Processor#prepareRequest()
                throw new IllegalArgumentException(sm.getString("iib.invalidRequestTarget"));
            }

            pos++;

        }

        request.unparsedURI().setBytes(buf, start, end - start);
        if (questionPos >= 0) {
            request.queryString().setBytes(buf, questionPos + 1,
                                           end - questionPos - 1);
            request.requestURI().setBytes(buf, start, questionPos - start);
        } else {
            request.requestURI().setBytes(buf, start, end - start);
        }

        // Spec says single SP but also says be tolerant of multiple SP and/or HT
        while (space) {
            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }
            if (buf[pos] == Constants.SP || buf[pos] == Constants.HT) {
                pos++;
            } else {
                space = false;
            }
        }

        // Mark the current buffer position
        start = pos;
        end = 0;

        //
        // Reading the protocol
        // Protocol is always "HTTP/" DIGIT "." DIGIT
        //
        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.CR) {
                end = pos;
            } else if (buf[pos] == Constants.LF) {
                if (end == 0)
                    end = pos;
                eol = true;
            } else if (!HttpParser.isHttpProtocol(buf[pos])) {
                throw new IllegalArgumentException(sm.getString("iib.invalidHttpProtocol"));
            }

            pos++;

        }

        if ((end - start) > 0) {
            request.protocol().setBytes(buf, start, end - start);
        } else {
            request.protocol().setString("");
        }

        return true;

    }


    /**
     * Parse the HTTP headers.
     */
    @Override
    public boolean parseHeaders()
        throws IOException {
        if (!parsingHeader) {
            throw new IllegalStateException(
                    sm.getString("iib.parseheaders.ise.error"));
        }

        while (parseHeader()) {
            // Loop until we run out of headers
        }

        parsingHeader = false;
        end = pos;
        return true;
    }


    /**
     * Parse an HTTP header.
     *
     * @return false after reading a blank line (which indicates that the
     * HTTP header parsing is done
     */
    @SuppressWarnings("null") // headerValue cannot be null
    private boolean parseHeader()
        throws IOException {

        //
        // Check for blank line
        //

        byte chr = 0;
        while (true) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            chr = buf[pos];

            if (chr == Constants.CR) { // 回车
                // Skip
            } else if (chr == Constants.LF) { // 换行
                pos++;
                return false;
                // 在解析某一行时遇到一个回车换行了，则表示请求头的数据结束了
            } else {
                break;
            }

            pos++;

        }

        // Mark the current buffer position
        int start = pos;

        //
        // Reading the header name
        // Header name is always US-ASCII
        //

        boolean colon = false;
        MessageBytes headerValue = null;

        while (!colon) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.COLON) {
                colon = true;
                headerValue = headers.addValue(buf, start, pos - start);
            } else if (!HttpParser.isToken(buf[pos])) {
                // Non-token characters are illegal in header names
                // Parsing continues so the error can be reported in context
                // skipLine() will handle the error
                skipLine(start);
                return true;
            }

            chr = buf[pos];
            if ((chr >= Constants.A) && (chr <= Constants.Z)) {
                buf[pos] = (byte) (chr - Constants.LC_OFFSET);
            }

            pos++;

        }

        // Mark the current buffer position
        start = pos;
        int realPos = pos;

        //
        // Reading the header value (which can be spanned over multiple lines)
        //

        boolean eol = false;
        boolean validLine = true;

        while (validLine) {

            boolean space = true;

            // Skipping spaces
            while (space) {

                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill())
                        throw new EOFException(sm.getString("iib.eof.error"));
                }

                if ((buf[pos] == Constants.SP) || (buf[pos] == Constants.HT)) {
                    pos++;
                } else {
                    space = false;
                }

            }

            int lastSignificantChar = realPos;

            // Reading bytes until the end of the line
            while (!eol) {

                // Read new bytes if needed
                if (pos >= lastValid) {
                    if (!fill())
                        throw new EOFException(sm.getString("iib.eof.error"));
                }

                if (buf[pos] == Constants.CR) {
                    // Skip
                } else if (buf[pos] == Constants.LF) {
                    eol = true;
                } else if (buf[pos] == Constants.SP) {
                    buf[realPos] = buf[pos];
                    realPos++;
                } else {
                    buf[realPos] = buf[pos];
                    realPos++;
                    lastSignificantChar = realPos;
                }

                pos++;

            }

            realPos = lastSignificantChar;

            // Checking the first character of the new line. If the character
            // is a LWS, then it's a multiline header

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            chr = buf[pos];
            if ((chr != Constants.SP) && (chr != Constants.HT)) {
                validLine = false;
            } else {
                eol = false;
                // Copying one extra space in the buffer (since there must
                // be at least one space inserted between the lines)
                buf[realPos] = chr;
                realPos++;
            }

        }

        // Set the header value
        headerValue.setBytes(buf, start, realPos - start);

        return true;

    }


    @Override
    public void recycle() {
        super.recycle();
        inputStream = null;
    }


    // ------------------------------------------------------ Protected Methods


    @Override
    protected void init(SocketWrapper<Socket> socketWrapper,
            AbstractEndpoint<Socket> endpoint) throws IOException {
        inputStream = socketWrapper.getSocket().getInputStream();
    }



    private void skipLine(int start) throws IOException {
        boolean eol = false;
        int lastRealByte = start;
        if (pos - 1 > start) {
            lastRealByte = pos - 1;
        }

        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.CR) {
                // Skip
            } else if (buf[pos] == Constants.LF) {
                eol = true;
            } else {
                lastRealByte = pos;
            }
            pos++;
        }

        if (rejectIllegalHeaderName || log.isDebugEnabled()) {
            String message = sm.getString("iib.invalidheader", new String(buf, start,
                    lastRealByte - start + 1, Charset.forName("ISO-8859-1")));
            if (rejectIllegalHeaderName) {
                throw new IllegalArgumentException(message);
            }
            log.debug(message);
        }
    }

    /**
     * Fill the internal buffer using data from the underlying input stream.
     *
     * @return false if at end of stream
     */
    protected boolean fill() throws IOException {
        return fill(true);
    }


    /**
     * bio 阻塞的
     */
    @Override
    protected boolean fill(boolean block) throws IOException {

        int nRead = 0;

        if (parsingHeader) {

            //如果请求行 + 请求头的大小 > 8kb 抛异常
            // 如果还在解析请求头，lastValid表示当前解析数据的下标位置，如果该位置等于buf的长度了，表示请求头的数据超过buf了。
            if (lastValid == buf.length) {
                throw new IllegalArgumentException
                    (sm.getString("iib.requestheadertoolarge.error"));
            }

            //阻塞的
            // 从inputStream中读取数据（内核缓冲区到应用缓冲区），len表示要读取的数据长度，pos表示把从inputStream读到的数据放在buf的pos位置
            // nRead表示真实读取到的数据
            nRead = inputStream.read(buf, pos, buf.length - lastValid);
            if (nRead > 0) {
                lastValid = pos + nRead; // 移动lastValid
            }

        } else {


            // 当读取请求体的数据时
            //end表示请求头结束的位置 也即请求体开始的位置
            // buf.length - end表示最多还能存放多少请求体数据，如果小于4500，那么就新生成一个byte数组，这个新的数组专门用来盛放请求体
            if (buf.length - end < 4500) {
                // In this case, the request header was really large, so we allocate a
                // brand new one; the old one will get GCed when subsequent requests
                // clear all references
                //旧的数组还被Request的里的method和uri（ByteChunk）所指向 所以method和uri等还是能拿到的
                buf = new byte[buf.length];
                end = 0;
            }
            pos = end;
            lastValid = pos;
            nRead = inputStream.read(buf, pos, buf.length - lastValid);
            if (nRead > 0) {
                lastValid = pos + nRead;
            }

        }

        return (nRead > 0);

    }


    // ------------------------------------- InputStreamInputBuffer Inner Class


    /**
     * This class is an input buffer which will read its data from an input
     * stream.
     */
    protected class InputStreamInputBuffer
        implements InputBuffer {


        /**
         * InputBuffer会从操作系统的RecvBuf中读取数据 fill()方法
         * 从InputBuffer中读取剩余数据到ByteChunk(实际上标记)，使用ByteChunk标记buffer中的剩余数据（pos到lastValid）
         * 此时buffer中的数据被读完了(pos 等于 lastValid) 此时就可以根据ByteChunk读取buffer中的数据
         * nRead表示读到了多少了数据
         */
        @Override
        public int doRead(ByteChunk chunk, Request req )
            throws IOException {

            // 如果buf中的数据都处理完了，则继续从底层获取数据
            if (pos >= lastValid) {
                if (!fill())
                    return -1;
            }

            int length = lastValid - pos;
            chunk.setBytes(buf, pos, length);
            // 因为这里是读取请求体，在解析请求行，请求头时，pos是每解析一个字符就移动一下，
            // 而这里不一样，这里只是负责把请求体的数据读出来即可，对于tomcat来说并不用这部分数据，所以直接把pos移动到lastValid位置
            //表示InputBuffer的数据被读完了
            pos = lastValid;


            return (length);
        }
    }
}
