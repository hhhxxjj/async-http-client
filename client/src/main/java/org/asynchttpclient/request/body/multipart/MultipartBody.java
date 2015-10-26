/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.request.body.multipart;

import static org.asynchttpclient.util.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.List;

import org.asynchttpclient.netty.request.body.BodyChunkedInput;
import org.asynchttpclient.request.body.RandomAccessBody;
import org.asynchttpclient.request.body.multipart.part.MultipartPart;
import org.asynchttpclient.request.body.multipart.part.MultipartState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultipartBody implements RandomAccessBody {

    private final static Logger LOGGER = LoggerFactory.getLogger(MultipartBody.class);

    private final List<MultipartPart<? extends Part>> parts;
    private final String contentType;
    private final byte[] boundary;
    private final long contentLength;
    private int currentPartIndex;
    private boolean done = false;

    public MultipartBody(List<MultipartPart<? extends Part>> parts, String contentType, byte[] boundary) {
        assertNotNull(parts, "parts");
        this.boundary = boundary;
        this.contentType = contentType;
        this.parts = parts;
        this.contentLength = computeContentLength();
    }

    private long computeContentLength() {
        try {
            long total = 0;
            for (MultipartPart<? extends Part> part : parts) {
                long l = part.length();
                if (l < 0) {
                    return -1;
                }
                total += l;
            }
            return total;
        } catch (Exception e) {
            LOGGER.error("An exception occurred while getting the length of the parts", e);
            return 0L;
        }
    }

    public void close() throws IOException {
        for (MultipartPart<? extends Part> part: parts) {
            part.close();
        }
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getBoundary() {
        return boundary;
    }

    // Regular Body API
    public BodyState transferTo(ByteBuffer target) throws IOException {

        if (done)
            return BodyState.STOP;

        while (target.hasRemaining() && !done) {
            MultipartPart<? extends Part> currentPart = parts.get(currentPartIndex);
            currentPart.transferTo(target);

            if (currentPart.getState() == MultipartState.DONE) {
                currentPartIndex++;
                if (currentPartIndex == parts.size()) {
                    done = true;
                }
            }
        }

        return BodyState.CONTINUE;
    }

    // RandomAccessBody API, suited for HTTP but not for HTTPS (zero-copy)
    @Override
    public long transferTo(WritableByteChannel target) throws IOException {

        if (done)
            return -1L;

        long transferred = 0L;
        boolean slowTarget = false;

        while (transferred < BodyChunkedInput.DEFAULT_CHUNK_SIZE && !done && !slowTarget) {
            MultipartPart<? extends Part> currentPart = parts.get(currentPartIndex);
            transferred += currentPart.transferTo(target);
            slowTarget = currentPart.isTargetSlow();

            if (currentPart.getState() == MultipartState.DONE) {
                currentPartIndex++;
                if (currentPartIndex == parts.size()) {
                    done = true;
                }
            }
        }

        return transferred;
    }
}
