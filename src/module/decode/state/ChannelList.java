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
package module.decode.state;

import audio.AudioPanel;
import controller.channel.Channel;
import controller.channel.ChannelEvent;
import controller.channel.ChannelEvent.Event;
import controller.channel.ChannelEventListener;
import controller.channel.ChannelModel;
import controller.channel.ChannelProcessingManager;
import icon.IconManager;
import net.miginfocom.swing.MigLayout;
import settings.ColorSetting.ColorSettingName;
import settings.ColorSettingMenuItem;
import settings.ColorSettingResetMenuItem;
import settings.SettingsManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gui wrapper for the list of currently processing primary channels
 */
public class ChannelList extends JPanel implements ChannelEventListener
{
    private static final long serialVersionUID = 1L;

    private Map<Channel, ChannelPanel> mDisplayedPanels = new ConcurrentHashMap<>();

    private ChannelModel mChannelModel;
    private ChannelProcessingManager mChannelProcessingManager;
    private IconManager mIconManager;
    private SettingsManager mSettingsManager;
    private AudioPanel mAudioPanel;

    public ChannelList(ChannelModel channelModel,
                       ChannelProcessingManager channelProcessingManager,
                       IconManager iconManager,
                       SettingsManager settingsManager,
                       AudioPanel audioPanel)
    {
        mChannelModel = channelModel;
        mChannelProcessingManager = channelProcessingManager;
        mIconManager = iconManager;
        mSettingsManager = settingsManager;
        mAudioPanel = audioPanel;

        init();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0,wrap 1", "[grow,fill]", "[]0[]"));
        setBackground(Color.BLACK);

        addMouseListener(new ListSelectionListener());

        add(mAudioPanel);

        JSeparator separator = new JSeparator(JSeparator.HORIZONTAL);
        add(separator);
    }

    @Override
    public void channelChanged(ChannelEvent event)
    {
        switch(event.getEvent())
        {
            case NOTIFICATION_PROCESSING_START:
                addChannelPanel(event.getChannel());
                break;
            case NOTIFICATION_PROCESSING_STOP:
                removeChannelPanel(event.getChannel());
                break;
            default:
                break;
        }
    }

    private void addChannelPanel(Channel channel)
    {
        if(!mDisplayedPanels.containsKey(channel))
        {
            ChannelPanel channelPanel = new ChannelPanel(mChannelModel,
                mChannelProcessingManager, mIconManager, mSettingsManager, channel);

            add(channelPanel);

            mDisplayedPanels.put(channel, channelPanel);

            revalidate();
            repaint();
        }
    }

    public void removeChannelPanel(Channel channel)
    {
        if(mDisplayedPanels.containsKey(channel))
        {
            ChannelPanel panel = mDisplayedPanels.remove(channel);

            if(panel != null)
            {
                remove(panel);

                panel.dispose();
            }

            revalidate();
            repaint();
        }
    }

    public class ListSelectionListener implements MouseListener
    {
        @Override
        public void mouseClicked(MouseEvent e)
        {
            Component component = getComponentAt(e.getPoint());

            if(component instanceof ChannelPanel)
            {
                ChannelPanel panel = (ChannelPanel) component;

                Channel channel = panel.getChannel();

                if(e.getButton() == MouseEvent.BUTTON1)
                {
                    if(channel.isSelected())
                    {
                        mChannelModel.broadcast(new ChannelEvent(channel, Event.REQUEST_DESELECT));
                    }
                    else
                    {
                        mChannelModel.broadcast(new ChannelEvent(channel, Event.REQUEST_SELECT));
                    }
                }
                else if(e.getButton() == MouseEvent.BUTTON3)
                {
                    JPopupMenu popupMenu = new JPopupMenu();

                    JMenu menu = panel.getContextMenu();

                    if(menu != null)
                    {
                        popupMenu.add(menu);
                    }

                    JMenu colorMenu = new JMenu("Color");
                    popupMenu.add(colorMenu);

                    JMenu backgroundMenu = new JMenu("Background");
                    colorMenu.add(backgroundMenu);

                    backgroundMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_BACKGROUND));
                    backgroundMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_BACKGROUND));

                    JMenu channelsMenu = new JMenu("Channel State");
                    colorMenu.add(channelsMenu);

                    JMenu callMenu = new JMenu("Call");
                    channelsMenu.add(callMenu);

                    callMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_CALL));
                    callMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_CALL));
                    callMenu.add(new JSeparator());

                    callMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_CALL));
                    callMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_CALL));

                    JMenu controlMenu = new JMenu("Control");
                    channelsMenu.add(controlMenu);

                    controlMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_CONTROL));
                    controlMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_CONTROL));
                    controlMenu.add(new JSeparator());

                    controlMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_CONTROL));
                    controlMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_CONTROL));

                    JMenu dataMenu = new JMenu("Data");
                    channelsMenu.add(dataMenu);

                    dataMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_DATA));
                    dataMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_DATA));
                    dataMenu.add(new JSeparator());

                    dataMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_DATA));
                    dataMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_DATA));

                    JMenu fadeMenu = new JMenu("Fade");
                    channelsMenu.add(fadeMenu);

                    fadeMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_FADE));
                    fadeMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_FADE));
                    fadeMenu.add(new JSeparator());

                    fadeMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_FADE));
                    fadeMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_FADE));

                    JMenu idleMenu = new JMenu("Idle");
                    channelsMenu.add(idleMenu);

                    idleMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_IDLE));
                    idleMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_IDLE));
                    idleMenu.add(new JSeparator());

                    idleMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_IDLE));
                    idleMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_IDLE));

                    JMenu noTunerMenu = new JMenu("No Tuner");
                    channelsMenu.add(noTunerMenu);

                    noTunerMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_NO_TUNER));
                    noTunerMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_MIDDLE_NO_TUNER));
                    noTunerMenu.add(new JSeparator());

                    noTunerMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_NO_TUNER));
                    noTunerMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_GRADIENT_TOP_NO_TUNER));

                    JMenu labelMenu = new JMenu("Labels");
                    colorMenu.add(labelMenu);

                    labelMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_LABEL_AUX_DECODER));
                    labelMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_LABEL_AUX_DECODER));
                    labelMenu.add(new JSeparator());

                    labelMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_LABEL_DECODER));
                    labelMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_LABEL_DECODER));
                    labelMenu.add(new JSeparator());

                    labelMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_LABEL_DETAILS));
                    labelMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_LABEL_DETAILS));

                    JMenu selectedMenu = new JMenu("Selected");
                    colorMenu.add(selectedMenu);

                    selectedMenu.add(new ColorSettingMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_SELECTED_CHANNEL));
                    selectedMenu.add(new ColorSettingResetMenuItem(mSettingsManager,
                        ColorSettingName.CHANNEL_STATE_SELECTED_CHANNEL));

                    popupMenu.show(ChannelList.this, e.getX(), e.getY());
                }
            }
        }

        public void mousePressed(MouseEvent e)
        {
        }

        public void mouseReleased(MouseEvent e)
        {
        }

        public void mouseEntered(MouseEvent e)
        {
        }

        public void mouseExited(MouseEvent e)
        {
        }
    }
}
