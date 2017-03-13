package module.decode.p25;

import alias.AliasList;
import instrument.Instrumentable;
import module.decode.Decoder;
import module.decode.DecoderType;
import source.tuner.frequency.IFrequencyChangeListener;
import source.tuner.frequency.IFrequencyChangeProvider;

public abstract class P25Decoder extends Decoder implements IFrequencyChangeListener, IFrequencyChangeProvider, Instrumentable
{
	private P25MessageProcessor mMessageProcessor;
	
	public P25Decoder( AliasList aliasList )
	{
        mMessageProcessor = new P25MessageProcessor( aliasList );
        mMessageProcessor.setMessageListener( this );
	}
	
	public void dispose()
	{
		super.dispose();
		
		mMessageProcessor.dispose();
		mMessageProcessor = null;
	}

	@Override
    public DecoderType getDecoderType()
    {
	    return DecoderType.P25_PHASE1;
    }

	protected P25MessageProcessor getMessageProcessor()
	{
		return mMessageProcessor;
	}
	
	public abstract Modulation getModulation();
	
	public enum Modulation
	{ 
		CQPSK( "Simulcast (LSM)", "LSM" ),
		C4FM( "Normal (C4FM)", "C4FM" );
		
		private String mLabel;
		private String mShortLabel;
		
		private Modulation( String label, String shortLabel )
		{
			mLabel = label;
			mShortLabel = shortLabel;
		}
		
		public String getLabel()
		{
			return mLabel;
		}
		
		public String getShortLabel()
		{
			return mShortLabel;
		}
		
		public String toString()
		{
			return getLabel();
		}
	}
}
