/*******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2017 Dennis Sheirer
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
package module.decode.mpt1327;

import alias.AliasList;
import bits.MessageFramer;
import bits.SyncPattern;
import dsp.filter.FilterFactory;
import dsp.filter.Filters;
import dsp.filter.fir.FIRFilterSpecification;
import dsp.filter.fir.real.RealFIRFilter_RB_RB;
import dsp.filter.halfband.real.HalfBandFilter_RB_RB;
import dsp.fsk.FSK2Decoder;
import dsp.fsk.FSK2Decoder.Output;
import instrument.Instrumentable;
import instrument.tap.Tap;
import instrument.tap.TapGroup;
import instrument.tap.stream.BinaryTap;
import instrument.tap.stream.FloatBufferTap;
import instrument.tap.stream.FloatTap;
import module.decode.Decoder;
import module.decode.DecoderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.Broadcaster;
import sample.Listener;
import sample.real.IFilteredRealBufferListener;
import sample.real.RealBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * MPT1327 Decoder - 1200 baud 2FSK decoder that can process 48k sample rate
 * complex or floating point samples and output fully framed MPT1327 control and
 * traffic messages.
 */
public class MPT1327Decoder extends Decoder implements IFilteredRealBufferListener, Instrumentable
{
    private final static Logger mLog = LoggerFactory.getLogger(MPT1327Decoder.class);

    private static final FIRFilterSpecification HIGH_PASS_SPECIFICATION = FIRFilterSpecification.highPassBuilder()
        .sampleRate( 24000 )
        .stopBandCutoff( 800 )
        .stopBandAmplitude( 0.0 )
        .stopBandRipple( 0.03 )
        .passBandStart( 1000 )
        .passBandAmplitude( 1.0 )
        .passBandRipple( 0.08 )
        .build();

    private static float[] HIGHPASS_FILTER;

    static
    {
        try
        {
            HIGHPASS_FILTER = FilterFactory.getTaps(HIGH_PASS_SPECIFICATION);
        }
        catch(Exception e)
        {
            mLog.error("Couldn't design MPT1327 highpass filter");
        }
    }

    /* Decimated sample rate ( 48,000 / 2 = 24,000 ) feeding the decoder */
    private static final int sDECIMATED_SAMPLE_RATE = 24000;

    /* Baud or Symbol Rate */
    private static final int sSYMBOL_RATE = 1200;

    /* Message length -- longest possible message is:
     *   4xREVS + 16xSYNC + 64xADD1 + 64xDCW1 + 64xDCW2 + 64xDCW3 + 64xDCW4 */
    private static final int sMESSAGE_LENGTH = 350;

    /* Instrumentation Taps */
    private static final String INSTRUMENT_HB1_FILTER_TO_HIGH_PASS =
        "Tap Point: Half-band Decimation Filter > < Low Pass Filter";
    private static final String INSTRUMENT_HIGH_PASS_TO_DECODER =
        "Tap Point: Low Pass Filter > < Decoder";
    private static final String INSTRUMENT_DECODER_TO_FRAMER =
        "Tap Point: Decoder > < Sync Detect/Message Framer";
    private List<TapGroup> mAvailableTaps;

    private HalfBandFilter_RB_RB mDecimationFilter;
    private RealFIRFilter_RB_RB mHighPassFilter;
    private FSK2Decoder mFSKDecoder;
    private Broadcaster<Boolean> mSymbolBroadcaster;
    private MessageFramer mControlMessageFramer;
    private MessageFramer mTrafficMessageFramer;
    private MPT1327MessageProcessor mMessageProcessor;

    public MPT1327Decoder(AliasList aliasList, Sync sync)
    {
        /**
         * Normal: 2FSK Decoder with inverted output
         * French: 2FSK Decoder with normal output
         */
        if(sync == Sync.NORMAL)
        {
            mFSKDecoder = new FSK2Decoder(24000, 1200, Output.INVERTED);
        }
        else if(sync == Sync.FRENCH)
        {
            mFSKDecoder = new FSK2Decoder(24000, 1200, Output.NORMAL);
        }
        else
        {
            throw new IllegalArgumentException("MPT1327 Decoder - unrecognized Sync type");
        }

        /* Decimation filter - 48000 / 2 = 24000 output */
        mDecimationFilter = new HalfBandFilter_RB_RB(Filters.FIR_HALF_BAND_31T_ONE_EIGHTH_FCO.getCoefficients(), 1.0002f, true);

        //High-pass filter the audio to remove any DC component or CTCSS tones. The audio has already been low-pass
        //filtered by the AudioDemodulation filter cutoff of 3400 Hz.
        mHighPassFilter = new RealFIRFilter_RB_RB(HIGHPASS_FILTER, 1.0f);
        mDecimationFilter.setListener(mHighPassFilter);

        mHighPassFilter.setListener(mFSKDecoder);

        mSymbolBroadcaster = new Broadcaster<Boolean>();
        mFSKDecoder.setListener(mSymbolBroadcaster);

        /* Message framer for control channel messages */
        mControlMessageFramer = new MessageFramer(sync.getControlSyncPattern().getPattern(), sMESSAGE_LENGTH);
        mSymbolBroadcaster.addListener(mControlMessageFramer);

        /* Message framer for traffic channel massages */
        mTrafficMessageFramer = new MessageFramer(sync.getTrafficSyncPattern().getPattern(), sMESSAGE_LENGTH);
        mSymbolBroadcaster.addListener(mTrafficMessageFramer);

        /* Fully decoded and framed messages processor */
        mMessageProcessor = new MPT1327MessageProcessor(aliasList);
        mMessageProcessor.setMessageListener(this);
        mControlMessageFramer.addMessageListener(mMessageProcessor);
        mTrafficMessageFramer.addMessageListener(mMessageProcessor);
    }

    @Override
    public Listener<RealBuffer> getFilteredRealBufferListener()
    {
        return mDecimationFilter;
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.MPT1327;
    }

    /**
     * Cleanup method
     */
    public void dispose()
    {
        super.dispose();

        mSymbolBroadcaster.dispose();

        mControlMessageFramer.dispose();

        mFSKDecoder.dispose();

        mDecimationFilter.dispose();

        mMessageProcessor.dispose();

        mTrafficMessageFramer.dispose();
    }

    /* Instrumentation Taps */
    @Override
    public List<TapGroup> getTapGroups()
    {
        if(mAvailableTaps == null)
        {
            mAvailableTaps = new ArrayList<TapGroup>();

            TapGroup group = new TapGroup("MPT-1327 Decoder");

            group.add(new FloatTap(INSTRUMENT_HB1_FILTER_TO_HIGH_PASS, 0, 0.5f));
            group.add(new FloatTap(INSTRUMENT_HIGH_PASS_TO_DECODER, 0, 0.5f));
            group.add(new BinaryTap(INSTRUMENT_DECODER_TO_FRAMER, 0, 0.025f));

            mAvailableTaps.add(group);

			/* Add the taps from the FSK decoder */
            mAvailableTaps.addAll(mFSKDecoder.getTapGroups());
        }

        return mAvailableTaps;
    }

    @Override
    public void registerTap(Tap tap)
    {
        /* Send request to decoder */
        mFSKDecoder.registerTap(tap);

        switch(tap.getName())
        {
            case INSTRUMENT_HB1_FILTER_TO_HIGH_PASS:
                FloatBufferTap hb1Tap = (FloatBufferTap) tap;
                mDecimationFilter.setListener(hb1Tap);
                hb1Tap.setListener(mHighPassFilter);
                break;
            case INSTRUMENT_HIGH_PASS_TO_DECODER:
                FloatBufferTap lowTap = (FloatBufferTap) tap;
                mHighPassFilter.setListener(lowTap);
                lowTap.setListener(mFSKDecoder);
                break;
            case INSTRUMENT_DECODER_TO_FRAMER:
                BinaryTap decoderTap = (BinaryTap) tap;
                mFSKDecoder.setListener(decoderTap);
                decoderTap.setListener(mSymbolBroadcaster);
                break;
        }
    }

    @Override
    public void unregisterTap(Tap tap)
    {
        mFSKDecoder.unregisterTap(tap);

        switch(tap.getName())
        {
            case INSTRUMENT_HB1_FILTER_TO_HIGH_PASS:
                mDecimationFilter.setListener(mHighPassFilter);
                break;
            case INSTRUMENT_HIGH_PASS_TO_DECODER:
                mHighPassFilter.setListener(mFSKDecoder);
                break;
            case INSTRUMENT_DECODER_TO_FRAMER:
                mFSKDecoder.setListener(mSymbolBroadcaster);
                break;
        }
    }

    public enum Sync
    {
        NORMAL("Normal", SyncPattern.MPT1327_CONTROL, SyncPattern.MPT1327_TRAFFIC),
        FRENCH("French", SyncPattern.MPT1327_CONTROL_FRENCH, SyncPattern.MPT1327_TRAFFIC_FRENCH);

        private String mLabel;
        private SyncPattern mControlSyncPattern;
        private SyncPattern mTrafficSyncPattern;

        private Sync(String label, SyncPattern controlPattern, SyncPattern trafficPattern)
        {
            mLabel = label;
            mControlSyncPattern = controlPattern;
            mTrafficSyncPattern = trafficPattern;
        }

        public String getLabel()
        {
            return mLabel;
        }

        public SyncPattern getControlSyncPattern()
        {
            return mControlSyncPattern;
        }

        public SyncPattern getTrafficSyncPattern()
        {
            return mTrafficSyncPattern;
        }

        public String toString()
        {
            return getLabel();
        }
    }

    @Override
    public void reset()
    {
        mControlMessageFramer.reset();
        mTrafficMessageFramer.reset();
    }

    @Override
    public void start(ScheduledExecutorService executor)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void stop()
    {
        // TODO Auto-generated method stub
    }
}
