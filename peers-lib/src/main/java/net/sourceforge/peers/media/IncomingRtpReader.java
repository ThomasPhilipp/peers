/*
    This file is part of Peers, a java SIP softphone.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
    
    Copyright 2007, 2008, 2009, 2010 Yohann Martineau 
*/

package net.sourceforge.peers.media;

import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.PriorityBlockingQueue;

import net.sourceforge.peers.Logger;
import net.sourceforge.peers.rtp.RFC3551;
import net.sourceforge.peers.rtp.RtpListener;
import net.sourceforge.peers.rtp.RtpPacket;
import net.sourceforge.peers.rtp.RtpSession;
import net.sourceforge.peers.sdp.Codec;

/**
 * Based on example given at https://sourceforge.net/p/peers/discussion/683023/thread/89b94e85/
 * which extends the original implementation with a Jitter.
 *
 * Open points:
 * -) capcity = 30. Is it to high?
 * -) swapped timestamp in comparator. Is it the same in HMK?
 */
public class IncomingRtpReader implements RtpListener {

    private RtpSession rtpSession;
    private AbstractSoundManager soundManager;
    private Decoder decoder;

    private PriorityBlockingQueue<RtpPacket> jitBuffer;
    private boolean queueReady;
    private int bufferMinSize;
    private long lastTimestampPlayed;

    public IncomingRtpReader(RtpSession rtpSession, AbstractSoundManager soundManager, Codec codec, Logger logger) throws IOException {
        this(rtpSession, soundManager, codec, logger, 30);
    }

    public IncomingRtpReader(RtpSession rtpSession, AbstractSoundManager soundManager, Codec codec, Logger logger,  int capacity) throws IOException {
        logger.debug("playback codec:" + codec.toString().trim());
        this.rtpSession = rtpSession;
        this.soundManager = soundManager;

        switch (codec.getPayloadType()) {
            case RFC3551.PAYLOAD_TYPE_PCMU:
                decoder = new PcmuDecoder();
                break;
            case RFC3551.PAYLOAD_TYPE_PCMA:
                decoder = new PcmaDecoder();
                break;
            default:
                throw new RuntimeException("unsupported payload type");
        }

        this.queueReady = false;
        this.lastTimestampPlayed = -1;
        this.bufferMinSize = capacity;
        this.jitBuffer = new PriorityBlockingQueue<>(this.bufferMinSize, new RtpPacketComparator());

        rtpSession.addRtpListener(this);
    }
    
    public void start() {
        rtpSession.start();

        //Player will start when enough packets are enqueued
        Runnable aRunnable = new Runnable() {
            @Override
            public void run() {
                while(!queueReady){
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {}
                    //System.out.println("[" + (new Date()).getTime() + "] " + " WAIT FOR QUEUE");
                }
                RtpPacket rtpPacket = null;
                int readPacketFailures = 0;
                while(readPacketFailures < 3){
                    while( ( rtpPacket = jitBuffer.poll() ) != null ){
                        //System.out.println("[" + (new Date()).getTime() + "] " + "Playing packet with timestamp "+rtpPacket.getTimestamp());
                        playRtpPacket(rtpPacket);
                        lastTimestampPlayed = rtpPacket.getTimestamp();
                        readPacketFailures = 0;
                    }
                    //queue is empty: try again after some time to make sure transmission is over
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {}
                    readPacketFailures++;
                }
                jitBuffer.clear();
                jitBuffer = null;
            }
        };

        Thread DoCall = new Thread(aRunnable);
        DoCall.start();
    }

    @Override
    public void receivedRtpPacket(RtpPacket rtpPacket) {
        if(rtpPacket == null)
            return;

        // Throw away packet if it is older than last packet played
        if(lastTimestampPlayed > rtpPacket.getTimestamp()){
            System.out.println("[" + (new Date()).getTime() + "] " + "Ignored packet with timestamp " + rtpPacket.getTimestamp() );
            return;
        }

        // Insert packet in queue
        jitBuffer.add(rtpPacket);
        //System.out.println("[" + (new Date()).getTime() + "] " + "Stored packet with timestamp  " + rtpPacket.getTimestamp() + " - QUEUE SIZE:" + jitBuffer.size());
        //once queue is large enough, let reader player send packets to soundManager
        if(!queueReady && jitBuffer.size() >= bufferMinSize){
            queueReady = true;
        }


    }

    public void playRtpPacket(RtpPacket rtpPacket) {
        if (rtpPacket == null) {
            System.out.println("[" + (new Date()).getTime() + "] " + "Could not play Rtp packet " + rtpPacket.getTimestamp() );
            return;
        }

        byte[] rawBuf = decoder.process(rtpPacket.getData());
        if (soundManager != null) {
            soundManager.writeData(rawBuf, 0, rawBuf.length);
        }
    }

    //Packet comparator for PriorityBlockingQueue
    class RtpPacketComparator implements Comparator<RtpPacket> {
        public int compare(RtpPacket p1, RtpPacket p2){
            if(p1.getTimestamp() > p2.getTimestamp())
                return -1; // reverse to original
            if(p1.getTimestamp() < p2.getTimestamp())
                return 1; // reverse to original
            return 0;
        }
    }

}
