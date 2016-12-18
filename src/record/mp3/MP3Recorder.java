/*******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2016 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 ******************************************************************************/
package record.mp3;

import audio.AudioPacket;
import audio.IAudioPacketListener;
import audio.convert.MP3AudioConverter;
import module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import record.AudioRecorder;
import record.wave.AudioPacketMonoWaveReader;
import sample.Listener;
import util.TimeStamp;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MP3 recorder for converting 8 kHz PCM audio packets to MP3 and writing to .mp3 file.
 */
public class MP3Recorder extends AudioRecorder
{
    private final static Logger mLog = LoggerFactory.getLogger(MP3Recorder.class);

    public static final int MP3_BIT_RATE = 16;
    public static final boolean CONSTANT_BIT_RATE = false;

    private MP3AudioConverter mMP3Converter;

    /**
     * MP3 audio recorder module for converting audio packets to 16 kHz constant bit rate MP3 format and
     * recording to a file.
     *
     * @param path to the output file.  File name should include the .mp3 file extension.
     */
    public MP3Recorder(Path path)
    {
        super(path);

        mMP3Converter = new MP3AudioConverter(MP3_BIT_RATE, CONSTANT_BIT_RATE);
    }

    @Override
    protected void record(List<AudioPacket> audioPackets) throws IOException
    {
        OutputStream outputStream = getOutputStream();

        if(outputStream != null)
        {
            processMetadata(audioPackets);

            byte[] mp3Audio = mMP3Converter.convert(audioPackets);

            outputStream.write(mp3Audio);
        }
    }

    /**
     * Processes audio metadata contained in the audio packets and converts the metadata to MP3 ID3 metadata tags and
     * writes the ID3 tags to the output stream.
     * @param audioPackets
     */
    private void processMetadata(List<AudioPacket> audioPackets)
    {
        //TODO: detect metadata changes and write out ID3 tags to the MP3 stream
    }

    public static void main(String[] args)
    {
        mLog.debug("Starting ...");

//        Path inputPath = Paths.get("/home/denny/Music/PCM.wav");
//        Path outputPath = Paths.get("/home/denny/Music/denny_test/PCM.mp3");
//
//        mLog.debug("Reading: " + inputPath.toString());
//        mLog.debug("Writing: " + outputPath.toString());
//
//        final MP3Recorder recorder = new MP3Recorder(outputPath);
//        recorder.start(Executors.newSingleThreadScheduledExecutor());
//
//        try (AudioPacketMonoWaveReader reader = new AudioPacketMonoWaveReader(inputPath, true))
//        {
//            reader.setListener(recorder);
//            reader.read();
//            recorder.stop();
//        }
//        catch (IOException e)
//        {
//            mLog.error("Error", e);
//        }
//
        Path outputPath = Paths.get("/home/denny/Music/Silence_test_10_seconds.mp3");

        mLog.debug("Writing: " + outputPath.toString());

        final MP3Recorder recorder = new MP3Recorder(outputPath);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        recorder.start(executor);

        long duration = 1165;
        int length = (int)(duration * 8);   //8000 Hz sample rate
        float[] silence = new float[length];
        AudioPacket silencePacket = new AudioPacket(silence, null);

        for(int x = 0; x < 10; x++)
        {
            recorder.receive(silencePacket);
        }

        recorder.stop();

        executor.shutdown();

        mLog.debug("Finished");
    }
}