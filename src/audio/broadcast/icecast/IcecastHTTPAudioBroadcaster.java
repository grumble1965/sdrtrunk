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
package audio.broadcast.icecast;

import audio.AudioPacket;
import audio.broadcast.AudioBroadcaster;
import audio.broadcast.BroadcastState;
import audio.metadata.AudioMetadata;
import audio.metadata.Metadata;
import audio.metadata.MetadataType;
import controller.ThreadPoolManager;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.asynchttpclient.uri.Uri;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import properties.SystemProperties;

import java.net.ConnectException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class IcecastHTTPAudioBroadcaster extends AudioBroadcaster implements Publisher<ByteBuffer>
{
    private final static Logger mLog = LoggerFactory.getLogger( IcecastHTTPAudioBroadcaster.class );

    private static final String UTF8 = "UTF-8";
    private static final String HTTP_100_CONTINUE = "100-continue";
    private static final long RECONNECT_INTERVAL_MILLISECONDS = 15000; //15 seconds
    private long mLastConnectionAttempt = 0;
    private DefaultAsyncHttpClient mDefaultAsyncHttpClient;
    private Subscriber<? super ByteBuffer> mSubscriber;
    private List<AudioPacket> mPacketsToBroadcast = new ArrayList<>();
    private ConvertedAudioStreamSubscription mSubscription;
    private AtomicBoolean mConnecting = new AtomicBoolean();
    private boolean mFirstMetadataUpdateSuppressed = false;

    /**
     * Creates an Icecast 2 (v2.4.x or newer) compatible broadcaster using HTTP 1.1 protocol
     *
     * Note: use @see IcecastTCPAudioBroadcaster for Icecast 1 (v2.3.x and older).
     *
     * @param defaultAsyncHttpClient to use when communicating with a remote HTTP server
     * @param threadPoolManager
     * @param configuration to use when connecting to the remote server
     */
    public IcecastHTTPAudioBroadcaster(DefaultAsyncHttpClient defaultAsyncHttpClient,
                                       ThreadPoolManager threadPoolManager,
                                       IcecastHTTPConfiguration configuration)
    {
        super(threadPoolManager, configuration);
        mDefaultAsyncHttpClient = defaultAsyncHttpClient;
    }

    /**
     * Configuration information
     */
    private IcecastHTTPConfiguration getConfiguration()
    {
        return (IcecastHTTPConfiguration)getBroadcastConfiguration();
    }


    @Override
    protected void broadcastMetadata(AudioMetadata metadata)
    {
        if(connected() && mFirstMetadataUpdateSuppressed)
        {
            StringBuilder sb = new StringBuilder();

            if(metadata != null)
            {
                Metadata to = metadata.getMetadata(MetadataType.TO);

                sb.append("TO:");

                if(to != null)
                {
                    if(to.hasAlias())
                    {
                        sb.append(to.getAlias().getName());
                    }
                    else
                    {
                        sb.append(to.getValue());
                    }
                }
                else
                {
                    sb.append("UNKNOWN");
                }

                Metadata from = metadata.getMetadata(MetadataType.FROM);

                sb.append(" FROM:");

                if(from != null)
                {

                    if(from.hasAlias())
                    {
                        sb.append(from.getAlias().getName());
                    }
                    else
                    {
                        sb.append(from.getValue());
                    }
                }
                else
                {
                    sb.append("UNKNOWN");
                }
            }
            else
            {
                sb.append("Scanning ....");
            }

            try
            {
                if(mDefaultAsyncHttpClient == null)
                {
                    mDefaultAsyncHttpClient = new DefaultAsyncHttpClient();
                }

                String songEncoded = URLEncoder.encode(sb.toString(), UTF8);

                StringBuilder query = new StringBuilder();
                query.append("/admin/metadata?mode=updinfo");
                query.append("&mount=").append(getConfiguration().getMountPoint());
                query.append("&charset=UTF%2d8");
                query.append("&song=").append(songEncoded);

                Uri uri = new Uri(Uri.HTTP, null, getConfiguration().getHost(), getConfiguration().getPort(),
                    query.toString(), null);

                BoundRequestBuilder builder = mDefaultAsyncHttpClient.prepareGet(uri.toUrl());

                //Use Basic (base64) authentication
                Realm realm = new Realm.Builder(getConfiguration().getUserName(), getConfiguration().getPassword())
                    .setScheme(Realm.AuthScheme.BASIC).setUsePreemptiveAuth(true).build();
                builder.setRealm(realm);
                builder.addHeader(IcecastHeader.USER_AGENT.getValue(), SystemProperties.getInstance().getApplicationName());

                mDefaultAsyncHttpClient.executeRequest(builder.build());
            }
            catch(Exception e)
            {
                mLog.debug("Error while updating metadata", e);
            }
        }
        else if(connected())
        {
            mFirstMetadataUpdateSuppressed = true;
        }
    }

    /**
     * Broadcast converted audio chunks received from the parent class processor
     */
    @Override
    protected void broadcastAudio(byte[] audio)
    {
        if(connect())
        {
            if (mSubscription != null && mSubscription.hasDemand())
            {
                mSubscription.broadcast(ByteBuffer.wrap(audio));
            }
        }
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber)
    {
        //Remove existing subscription, if there is one
        if (mSubscription != null)
        {
            mSubscription.cancel();
        }

        //Create the subscription
        mSubscription = new ConvertedAudioStreamSubscription(subscriber);
    }

    /**
     * Indicates if the audio handler is currently connected to the remote server and capable of streaming audio.
     *
     * This method will attempt to establish a connection or reconnect to the streaming server.
     * @return true if the audio handler can stream audio
     */
    private boolean connect()
    {
        if(canConnect())
        {
            createConnection();
        }

        return connected();
    }


    /**
     * Disconnect from the remote server
     */
    public void disconnect()
    {

    }

    /**
     * Creates a connection to the remote server using the shoutcast configuration information.  Once disconnected
     * following a successful connection, attempts to reestablish a connection on a set interval
     */
    private void createConnection()
    {
        if(mConnecting.compareAndSet(false, true))
        {
            if(canConnect() && System.currentTimeMillis() - mLastConnectionAttempt >= RECONNECT_INTERVAL_MILLISECONDS)
            {
                mLog.debug("Creating a connection to icecast server");
                setBroadcastState(BroadcastState.CONNECTING);
                mLastConnectionAttempt = System.currentTimeMillis();

                Uri uri = new Uri(Uri.HTTP, null, getConfiguration().getHost(), getConfiguration().getPort(),
                        getConfiguration().getMountPoint(), null);

                BoundRequestBuilder requestBuilder = mDefaultAsyncHttpClient.preparePut(uri.toUrl());

                //Use Basic (base64) authentication
                Realm realm = new Realm.Builder(getConfiguration().getUserName(), getConfiguration().getPassword())
                        .setScheme(Realm.AuthScheme.BASIC).setUsePreemptiveAuth(true).build();
                requestBuilder.setRealm(realm);
                requestBuilder.setBody(this);

                //We don't want this request to timeout since it will exist while we are streaming
                requestBuilder.setRequestTimeout(-1);

                requestBuilder.addHeader(IcecastHeader.ACCEPT.getValue(), "*/*");
                requestBuilder.addHeader(IcecastHeader.CONTENT_TYPE.getValue(), getConfiguration().getBroadcastFormat().getValue());
                requestBuilder.addHeader(IcecastHeader.PUBLIC.getValue(), getConfiguration().isPublic() ? "1" : "0");

                if(getConfiguration().hasName())
                {
                    requestBuilder.addHeader(IcecastHeader.NAME.getValue(), getConfiguration().getName());
                }

                if(getConfiguration().hasDescription())
                {
                    requestBuilder.addHeader(IcecastHeader.DESCRIPTION.getValue(), getConfiguration().getDescription());
                }

                if(getConfiguration().hasURL())
                {
                    requestBuilder.addHeader(IcecastHeader.URL.getValue(), getConfiguration().getURL());
                }

                if(getConfiguration().hasGenre())
                {
                    requestBuilder.addHeader(IcecastHeader.GENRE.getValue(), getConfiguration().getGenre());
                }


                if(getConfiguration().hasBitRate())
                {
                    requestBuilder.addHeader(IcecastHeader.BITRATE.getValue(), String.valueOf(getConfiguration().getBitRate()));
                }

                requestBuilder.addHeader(IcecastHeader.USER_AGENT.getValue(),
                        SystemProperties.getInstance().getApplicationName());

                requestBuilder.addHeader(IcecastHeader.EXPECT.getValue(), HTTP_100_CONTINUE);

                requestBuilder.setRequestTimeout(1000);
                requestBuilder.execute(new AsyncHttpConnectionResponseHandler());
            }
            else
            {
                mConnecting.set(false);
            }
        }
    }

    public class AsyncHttpConnectionResponseHandler implements AsyncHandler<Response>
    {
        @Override
        public void onThrowable(Throwable throwable)
        {
            mLog.debug("Got a throwable!");
            if(throwable instanceof ConnectException)
            {
                if(throwable.getMessage().contains("Connection refused"))
                {
                    setBroadcastState(BroadcastState.NO_SERVER);
                }
            }
            else
            {
                mLog.error("Error connecting to remote server - " + throwable.getMessage());
                setBroadcastState(BroadcastState.BROADCAST_ERROR);
            }
            mConnecting.compareAndSet(true, false);
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart httpResponseBodyPart) throws Exception
        {
            mLog.debug("Got response body part");
            return State.CONTINUE;
        }

        @Override
        public State onStatusReceived(HttpResponseStatus httpResponseStatus) throws Exception
        {
            mLog.debug("Got response status: " + httpResponseStatus.toString());
            State returnState = State.ABORT;

            switch(httpResponseStatus.getStatusCode())
            {
                case 200: //200-OK
                    setBroadcastState(BroadcastState.CONNECTED);
                    returnState = State.CONTINUE;
                    break;
                case 100: //100-Continue
                    setBroadcastState(BroadcastState.CONNECTING);
                    returnState = State.CONTINUE;
                    break;
                case 401:
                    setBroadcastState(BroadcastState.INVALID_PASSWORD);
                    break;
                case 403:
                    String text = httpResponseStatus.getStatusText();

                    mLog.error("Error while connecting to Icecast server: " + httpResponseStatus.getStatusCode() +
                        " " + text);

                    if(text != null)
                    {
                        if(text.contains("Content-type not supported"))
                        {
                            setBroadcastState(BroadcastState.UNSUPPORTED_AUDIO_FORMAT);
                        }
                        else if(text.contains("too many sources"))
                        {
                            setBroadcastState(BroadcastState.MAX_SOURCES_EXCEEDED);
                        }
                        else if(text.contains("Mountpoint in use"))
                        {
                            setBroadcastState(BroadcastState.MOUNT_POINT_IN_USE);
                        }
                        else
                        {
                            setBroadcastState(BroadcastState.ERROR);
                        }
                    }
                    else
                    {
                        setBroadcastState(BroadcastState.ERROR);
                    }
                    break;
                case 500:
                    mLog.error("Error while connecting to Icecast server: " + httpResponseStatus.getStatusCode() +
                            " " + httpResponseStatus.getStatusText());
                    setBroadcastState(BroadcastState.REMOTE_SERVER_ERROR);
                    break;
                default:
                    mLog.error("Error while connecting to Icecast server - unrecognized status code: " +
                            httpResponseStatus.getStatusCode() + " " + httpResponseStatus.getStatusText());
                    setBroadcastState(BroadcastState.ERROR);
            }

            mConnecting.compareAndSet(true, false);

            return returnState;
        }

        @Override
        public State onHeadersReceived(HttpResponseHeaders httpResponseHeaders) throws Exception
        {
            mLog.debug("Got headers received");
            return State.CONTINUE;
        }

        @Override
        public Response onCompleted() throws Exception
        {
            mLog.debug("Got on completed");
            setBroadcastState(BroadcastState.DISCONNECTED);
            return null;
        }
    }

    /**
     * Subscription to monitor a converted audio stream subscriber and the subscriber's expressed demand for converted
     * audio byte buffers
     */
    public class ConvertedAudioStreamSubscription implements Subscription
    {
        private Subscriber<? super ByteBuffer> mSubscriber;
        private AtomicLong mDemand = new AtomicLong();

        /**
         * Constructs a subscription
         * @param subscriber to receive converted audio byte buffers
         */
        public ConvertedAudioStreamSubscription(Subscriber<? super ByteBuffer> subscriber)
        {
            mSubscriber = subscriber;
            mSubscriber.onSubscribe(this);
        }

        /**
         * Request method for the subscriber to register demand for audio byte buffers
         * @param requestedBufferCount to stream to the subscriber
         */
        @Override
        public void request(long requestedBufferCount)
        {
            mDemand.getAndAdd(requestedBufferCount);
        }

        /**
         * Indicates if this subscription currently has registered demand from the subscriber
         */
        public boolean hasDemand()
        {
            return mDemand.get() > 0;
        }

        /**
         * Broadcasts the buffer to the subscriber and decrements the internal buffer demand counter
         */
        public void broadcast(ByteBuffer buffer)
        {
            if(mSubscriber != null)
            {
                mSubscriber.onNext(buffer);
                mDemand.decrementAndGet();
            }
        }

        /**
         * Invoked by the subscriber to cancel the stream
         */
        @Override
        public void cancel()
        {
            mLog.debug("Subscriber has requested a cancel");
            stop();
        }
    }
}