/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.delay.sender;

import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.util.List;
import qunar.tc.qmq.MessageGroup;
import qunar.tc.qmq.base.BaseMessage;
import qunar.tc.qmq.config.NettyClientConfigManager;
import qunar.tc.qmq.delay.store.model.ScheduleSetRecord;
import qunar.tc.qmq.netty.client.NettyClient;
import qunar.tc.qmq.netty.exception.ClientSendException;
import qunar.tc.qmq.netty.exception.RemoteTimeoutException;
import qunar.tc.qmq.protocol.CommandCode;
import qunar.tc.qmq.protocol.Datagram;
import qunar.tc.qmq.protocol.RemotingHeader;
import qunar.tc.qmq.util.RemotingBuilder;
import qunar.tc.qmq.utils.Crc32;
import qunar.tc.qmq.utils.Flags;
import qunar.tc.qmq.utils.PayloadHolderUtils;

/**
 * @author xufeng.deng dennisdxf@gmail.com
 * @since 2018-07-23 16:33
 */
public class NettySender implements Sender {

    private final NettyClient client;

    public NettySender() {
        this.client = NettyClient.getClient();
        this.client.start(NettyClientConfigManager.get().getDefaultClientConfig());
    }

    @Override
    public Datagram send(MessageGroup messageGroup, List<ScheduleSetRecord> records, GroupSender groupSender)
            throws InterruptedException, RemoteTimeoutException, ClientSendException {
        Datagram requestDatagram = RemotingBuilder.buildRequestDatagram(CommandCode.SEND_MESSAGE, out -> {
            if (null == records || records.isEmpty()) {
                return;
            }
            for (ScheduleSetRecord record : records) {
                // 模拟消息序列化, 写入 out
                ByteBuffer in = record.getRecord();

                // skip crc
                in.getLong();
                int crcIndex = out.writerIndex();
                out.ensureWritable(8);
                out.writerIndex(crcIndex + 8);

                int messageStart = out.writerIndex();

                // write flag
                byte flag = in.get();
                out.writeByte(flag);

                // write create time
                long createTime = in.getLong();
                out.writeLong(createTime);

                // write receive time
                long receiveTime = in.getLong();
                out.writeLong(receiveTime);

                // write subject(partitionName)
                PayloadHolderUtils.writeString(messageGroup.getPartitionName(), out);

                // skip message subject
                PayloadHolderUtils.skipString(in);

                // copy left bytes
                out.writeBytes(in);

                int messageEndIndex = out.writerIndex();
                int messageLen = messageEndIndex - messageStart;

                // write crc
                out.writerIndex(crcIndex);
                out.writeLong(messageCrc(out, messageStart, messageLen));

                // reset index to the end
                out.writerIndex(messageEndIndex);
            }
        });
        requestDatagram.getHeader().setVersion(RemotingHeader.getScheduleTimeVersion());
        return client.sendSync(groupSender.getBrokerGroupInfo().getMaster(), requestDatagram, 5 * 1000);
    }

    private long messageCrc(ByteBuf out, int messageStart, int messageLength) {
        return Crc32.crc32(out.nioBuffer(messageStart, messageLength), 0, messageLength);
    }

    private void writeAttribute(ByteBuf buf, String key, String val) {
        PayloadHolderUtils.writeString(key, buf);
        PayloadHolderUtils.writeString(val, buf);
    }

    @Override
    public void shutdown() {
    }
}
