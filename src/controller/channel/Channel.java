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
package controller.channel;

import controller.config.Configuration;
import module.decode.DecoderFactory;
import module.decode.DecoderType;
import module.decode.config.AuxDecodeConfiguration;
import module.decode.config.DecodeConfiguration;
import module.log.EventLogType;
import module.log.config.EventLogConfiguration;
import record.RecorderType;
import record.config.RecordConfiguration;
import sample.Listener;
import source.SourceType;
import source.config.SourceConfigFactory;
import source.config.SourceConfigRecording;
import source.config.SourceConfigTuner;
import source.config.SourceConfiguration;
import source.tuner.TunerChannel;
import source.tuner.TunerChannel.Type;
import source.tuner.frequency.FrequencyChangeEvent;
import source.tuner.frequency.FrequencyChangeEvent.Event;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlSeeAlso;
import javax.xml.bind.annotation.XmlTransient;

@XmlSeeAlso({Configuration.class})
@XmlRootElement(name = "channel")
public class Channel extends Configuration implements Listener<FrequencyChangeEvent>
{
    // Standard channels are persisted and traffic channels are temporary
    public enum ChannelType
    {
        STANDARD, TRAFFIC
    }

    ;


    // Static unique channel identifier tracking
    private static int UNIQUE_ID = 0;

    private DecodeConfiguration mDecodeConfiguration = DecoderFactory.getDefaultDecodeConfiguration();
    private AuxDecodeConfiguration mAuxDecodeConfiguration =
        new AuxDecodeConfiguration();
    private SourceConfiguration mSourceConfiguration =
        SourceConfigFactory.getDefaultSourceConfiguration();
    private EventLogConfiguration mEventLogConfiguration =
        new EventLogConfiguration();
    private RecordConfiguration mRecordConfiguration = new RecordConfiguration();

    private String mAliasListName;
    private String mSystem = "System";
    private String mSite = "Site";
    private String mName = "Channel";

    private boolean mEnabled;
    private boolean mSelected;
    private TunerChannel mTunerChannel = null;

    private ChannelType mChannelType = ChannelType.STANDARD;

    private int mChannelID;
    private int mChannelFrequencyCorrection = 0;

    /**
     * Channel represents a complete set of configurations needed to setup and
     * manage a processing chain and/or manage as a set of business objects that
     * can be displayed in graphical components.
     *
     * @param channelName
     * @param channelType
     */
    public Channel(String channelName, ChannelType channelType)
    {
        this(channelName);
        mChannelType = channelType;
    }

    /**
     * Constructs a new standard channel with the specified channel name
     */
    public Channel(String channelName)
    {
        this();
        mName = channelName;
    }

    /**
     * Constructs a new standard channel with a default name of "Channel"
     */
    public Channel()
    {
        mChannelID = UNIQUE_ID++;
    }

    /**
     * Creates a (new) deep copy of this channel
     */
    public Channel copyOf()
    {
        Channel channel = new Channel(mName);
        channel.setSystem(mSystem);
        channel.setSite(mSite);
        channel.setAliasListName(mAliasListName);

        AuxDecodeConfiguration aux = new AuxDecodeConfiguration();

        for(DecoderType auxType : aux.getAuxDecoders())
        {
            aux.addAuxDecoder(auxType);
        }

        channel.setAuxDecodeConfiguration(aux);

        channel.setDecodeConfiguration(DecoderFactory.copy(mDecodeConfiguration));

        EventLogConfiguration log = new EventLogConfiguration();

        for(EventLogType logType : mEventLogConfiguration.getLoggers())
        {
            log.addLogger(logType);
        }

        channel.setEventLogConfiguration(log);

        RecordConfiguration record = new RecordConfiguration();

        for(RecorderType recordType : mRecordConfiguration.getRecorders())
        {
            record.addRecorder(recordType);
        }

        channel.setRecordConfiguration(record);

        channel.setSourceConfiguration(SourceConfigFactory.copy(mSourceConfiguration));

        return channel;
    }

    /**
     * Unique identifier for this channel.  Value is transient (not persisted).
     */
    @XmlTransient
    public int getChannelID()
    {
        return mChannelID;
    }

    /**
     * Indicates if this channel has been selected for prioritized audio output
     * and display of processing chain products
     */
    @XmlTransient
    public boolean isSelected()
    {
        return mSelected;
    }

    /**
     * Sets selection status of the channel.
     *
     * NOTE: this method is package private so that only the ChannelSelectionManager
     * can toggle the selection state.  Use the channel model to broadcast a
     * request select/deselect channel event.
     *
     * @param selected
     */
    void setSelected(boolean selected)
    {
        mSelected = selected;
    }

    @XmlTransient
    public ChannelType getChannelType()
    {
        return mChannelType;
    }

    /**
     * Returns the owning system for this channel.
     */
    @XmlAttribute(name = "system")
    public String getSystem()
    {
        return mSystem;
    }

    public void setSystem(String system)
    {
        mSystem = system;
    }

    public boolean hasSystem()
    {
        return mSystem != null;
    }

    /**
     * Returns the owning site for this channel.
     */
    @XmlAttribute(name = "site")
    public String getSite()
    {
        return mSite;
    }

    public void setSite(String site)
    {
        mSite = site;
    }

    public boolean hasSite()
    {
        return mSite != null;
    }

    /**
     * Returns the name of this channel.
     */
    @XmlAttribute(name = "name")
    public String getName()
    {
        return mName;
    }

    /**
     * Sets the name of this channel.
     */
    public void setName(String name)
    {
        mName = name;
    }

    /**
     * Default display string for this channel: SYSTEM_SITE_NAME
     */
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(hasSystem() ? getSystem() : "SYSTEM");
        sb.append("_");
        sb.append(hasSite() ? getSite() : "SITE");
        sb.append("_");
        sb.append(getName());

        return sb.toString();
    }

    /**
     * Indicates if this channel is enabled for processing.
     */
    @XmlAttribute(name = "enabled")
    public boolean getEnabled()
    {
        return mEnabled;
    }

    /**
     * Indicates if this channel will automatically start processing on
     * application startup or if this channel is currently processing after
     * application startup.
     *
     * Note: this method is package private and is intended to be managed
     * by a co-package central processing manager
     *
     * @see controller.channel.ChannelProcessingManager
     */
    void setEnabled(boolean enabled)
    {
        mEnabled = enabled;
    }

    /**
     * Returns the alias list that is used by this channel for looking up alias
     * values for the various identifiers produced by the decoder(s).
     */
    @XmlElement(name = "alias_list_name")
    public String getAliasListName()
    {
        return mAliasListName;
    }

    /**
     * Sets the alias list to be used for looking up the alias values for the
     * various identifiers produced by the decoder
     */
    public void setAliasListName(String name)
    {
        mAliasListName = name;
    }

    /**
     * Gets the primary decoder configuration used by this channel
     */
    @XmlElement(name = "decode_configuration")
    public DecodeConfiguration getDecodeConfiguration()
    {
        return mDecodeConfiguration;
    }

    /**
     * Sets the primary decoder configuration used by this channel
     */
    public void setDecodeConfiguration(DecodeConfiguration config)
    {
        mDecodeConfiguration = config;
    }

    /**
     * Gets the aux decoder configuration used by this channel.  Aux decoders
     * operate on demodulated audio
     */
    @XmlElement(name = "aux_decode_configuration")
    public AuxDecodeConfiguration getAuxDecodeConfiguration()
    {
        return mAuxDecodeConfiguration;
    }

    /**
     * Sets the decoder configuration used by this channel
     */
    public void setAuxDecodeConfiguration(AuxDecodeConfiguration config)
    {
        mAuxDecodeConfiguration = config;
    }

    /**
     * Returns the source configuration for this channel.  A channel source
     * identifies where the processing chain will get source sample data from.
     */
    @XmlElement(name = "source_configuration")
    public SourceConfiguration getSourceConfiguration()
    {
        return mSourceConfiguration;
    }

    /**
     * Sets the source configuration for this channel.
     */
    public void setSourceConfiguration(SourceConfiguration config)
    {
        mSourceConfiguration = config;

        //Clear the tune channel object so that it can be recreated if the
        //source configuration changes
        mTunerChannel = null;
    }

    /**
     * Returns the event logger configuration for this channel.
     */
    @XmlElement(name = "event_log_configuration")
    public EventLogConfiguration getEventLogConfiguration()
    {
        return mEventLogConfiguration;
    }

    /**
     * Sets the event logger configuration for this channel.
     */
    public void setEventLogConfiguration(EventLogConfiguration config)
    {
        mEventLogConfiguration = config;
    }

    /**
     * Returns the recorder configuration for this channel.
     */
    @XmlElement(name = "record_configuration")
    public RecordConfiguration getRecordConfiguration()
    {
        return mRecordConfiguration;
    }

    /**
     * Sets the recorder configuration for this channel.
     */
    public void setRecordConfiguration(RecordConfiguration config)
    {
        mRecordConfiguration = config;
    }

    /**
     * Convenience method to construct a tuner channel object representing a
     * tuner or recording source frequency and bandwidth that can be used by
     * application components for graphically representing this channel.
     *
     * If the source configuration is not a tuner or recording, this method
     * returns null.
     */
    @XmlTransient
    public TunerChannel getTunerChannel()
    {
        if(mTunerChannel == null)
        {
            if(mSourceConfiguration.getSourceType() == SourceType.TUNER)
            {
                SourceConfigTuner config = (SourceConfigTuner)mSourceConfiguration;

                mTunerChannel = new TunerChannel(Type.LOCKED, config.getFrequency(),
                    mDecodeConfiguration.getDecoderType().getChannelBandwidth());
            }
            else if(mSourceConfiguration.getSourceType() == SourceType.RECORDING)
            {
                SourceConfigRecording config =
                    (SourceConfigRecording)mSourceConfiguration;

                mTunerChannel = new TunerChannel(Type.LOCKED, config.getFrequency(),
                    mDecodeConfiguration.getDecoderType().getChannelBandwidth());
            }
        }

        return mTunerChannel;
    }

    /**
     * Convenience method to indicate if any part of this channel is contained
     * within the minimum and maximum frequency values.
     */
    public boolean isWithin(long minimum, long maximum)
    {
        TunerChannel tunerChannel = getTunerChannel();

        return tunerChannel != null && tunerChannel.overlaps(minimum, maximum);
    }

    /**
     * Convenience method to make the current channel frequency correction value
     * available for use outside of a constructed processing chain while maintaining
     * linkage to the source channel.  This hack allows the correction value to
     * be used to visually show in the spectral display.
     */
    @Override
    public void receive(FrequencyChangeEvent event)
    {
        if(event.getEvent() == Event.NOTIFICATION_CHANNEL_FREQUENCY_CORRECTION_CHANGE)
        {
            mChannelFrequencyCorrection = event.getValue().intValue();
        }
    }

    /**
     * Current channel frequency correction value (when this channel is processing)
     * @return
     */
    @XmlTransient
    public int getChannelFrequencyCorrection()
    {
        return mChannelFrequencyCorrection;
    }

    /**
     * Resets frequency correction value to 0
     */
    public void resetFrequencyCorrection()
    {
        mChannelFrequencyCorrection = 0;
    }
}
