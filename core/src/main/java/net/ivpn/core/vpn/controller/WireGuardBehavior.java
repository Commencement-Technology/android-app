package net.ivpn.core.vpn.controller;

/*
 IVPN Android app
 https://github.com/ivpn/android-app

 Created by Oleksandr Mykhailenko.
 Copyright (c) 2023 IVPN Limited.

 This file is part of the IVPN Android app.

 The IVPN Android app is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as published by the Free
 Software Foundation, either version 3 of the License, or (at your option) any later version.

 The IVPN Android app is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 details.

 You should have received a copy of the GNU General Public License
 along with the IVPN Android app. If not, see <https://www.gnu.org/licenses/>.
*/

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.wireguard.android.backend.WireGuardUiService;
import com.wireguard.android.model.Tunnel;

import net.ivpn.core.IVPNApplication;
import net.ivpn.core.common.Mapper;
import net.ivpn.core.common.multihop.MultiHopController;
import net.ivpn.core.common.pinger.PingProvider;
import net.ivpn.core.rest.data.model.ServerType;
import net.ivpn.core.common.prefs.ServersRepository;
import net.ivpn.core.common.utils.DateUtil;
import net.ivpn.core.rest.Responses;
import net.ivpn.core.rest.data.model.Server;
import net.ivpn.core.rest.data.wireguard.ErrorResponse;
import net.ivpn.core.v2.connect.createSession.ConnectionState;
import net.ivpn.core.v2.dialog.Dialogs;
import net.ivpn.core.vpn.ServiceConstants;
import net.ivpn.core.vpn.VPNConnectionState;
import net.ivpn.core.vpn.controller.WireGuardKeyController.WireGuardKeysEventsListener;
import net.ivpn.core.vpn.wireguard.ConfigManager;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import kotlin.jvm.Volatile;

import static net.ivpn.core.v2.connect.createSession.ConnectionState.CONNECTED;
import static net.ivpn.core.v2.connect.createSession.ConnectionState.CONNECTING;
import static net.ivpn.core.v2.connect.createSession.ConnectionState.DISCONNECTING;
import static net.ivpn.core.v2.connect.createSession.ConnectionState.NOT_CONNECTED;
import static net.ivpn.core.v2.connect.createSession.ConnectionState.PAUSED;
import static net.ivpn.core.v2.connect.createSession.ConnectionState.PAUSING;

public class WireGuardBehavior extends VpnBehavior implements ServiceConstants, Tunnel.OnStateChangedListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(WireGuardBehavior.class);

    private List<VpnStateListener> listeners = new ArrayList<>();
    private PauseTimer timer;
    private BroadcastReceiver notificationActionReceiver;
    private ConnectionState state;
    private WireGuardKeyController keyController;

    private ServersRepository serversRepository;
    private ConfigManager configManager;
    private PingProvider pingProvider;
    private MultiHopController multiHopController;

    @Volatile
    private Server _fastestServer = null;
    private Observer<Server> fastestServerObserver = server -> {
        _fastestServer = server;
    };
    private LiveData<Server> fastestServer;
    private long pauseDuration = 0;

    @Inject
    WireGuardBehavior(WireGuardKeyController wireGuardKeyController,
                      ServersRepository serversRepository,
                      ConfigManager configManager,
                      PingProvider pingProvider,
                      MultiHopController multiHopController) {
        LOGGER.info("Creating");
        keyController = wireGuardKeyController;
        this.serversRepository = serversRepository;
        this.configManager = configManager;
        this.pingProvider = pingProvider;
        this.multiHopController = multiHopController;

        configManager.setListener(this);
        listeners.add(pingProvider.getVPNStateListener());

        fastestServer = pingProvider.getFastestServer();
        fastestServer.observeForever(fastestServerObserver);

        init();
    }

    private void init() {
        keyController.setKeysEventsListener(getWireGuardKeysEventsListener());
        timer = new PauseTimer(new PauseTimer.PauseTimerListener() {
            @Override
            public void onTick(long millisUntilFinished) {
                LOGGER.info("Will resume in " + DateUtil.formatNotificationTimerCountDown(millisUntilFinished));
                for (VpnStateListener listener : listeners) {
                    listener.onTimeTick(millisUntilFinished);
                }
            }

            @Override
            public void onFinish() {
                LOGGER.info("Should be resumed");
                resume();
                for (VpnStateListener listener : listeners) {
                    listener.onTimerFinish();
                }
            }
        });
        state = NOT_CONNECTED;
        registerReceivers();
    }

    @Override
    public void actionByUser() {
        LOGGER.info("actionByUser, state = " + state + " this = " + this);

        switch (state) {
            case CONNECTED:
            case CONNECTING: {
                startDisconnectProcess();
                break;
            }
            case PAUSING:
            case DISCONNECTING: {
                //ignore this case
                return;
            }
            case PAUSED:
            case NOT_CONNECTED: {
                startConnecting();
            }
        }
    }

    @Override
    public void addStateListener(VpnStateListener vpnStateListener) {
        LOGGER.info("setStateListener: ");
        listeners.add(vpnStateListener);
        if (vpnStateListener != null) {
            vpnStateListener.onConnectionStateChanged(state);
        }
    }

    @Override
    public void removeStateListener(VpnStateListener vpnStateListener) {
        LOGGER.info("removeStateListener: ");
        listeners.remove(vpnStateListener);
    }

    @Override
    public void destroy() {
        LOGGER.info("destroy, remove all registers and listeners");
        configManager.setListener(null);
        unregisterReceivers();
        stop();
        listeners.clear();
        fastestServer.removeObserver(fastestServerObserver);
    }

    @Override
    public void notifyVpnState() {
        sendConnectionState(timer.getMillisUntilFinished());
    }

    private void registerReceivers() {
        notificationActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                if (action.equals(NOTIFICATION_ACTION)) {
                    onNotificationAction(intent);
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NOTIFICATION_ACTION);

        LocalBroadcastManager.getInstance(IVPNApplication.application).registerReceiver(notificationActionReceiver, intentFilter);
    }

    private void unregisterReceivers() {
        LocalBroadcastManager.getInstance(IVPNApplication.application).unregisterReceiver(notificationActionReceiver);
    }

    private void onNotificationAction(Intent intent) {
        String actionExtra = intent.getStringExtra(NOTIFICATION_ACTION_EXTRA);
        if (actionExtra == null) {
            return;
        }
        LOGGER.info("onNotificationAction, actionExtra = " + actionExtra + " state = " + state);
        switch (actionExtra) {
            case DISCONNECT_ACTION: {
                behaviourListener.disconnect();
                break;
            }
            case PAUSE_ACTION: {
                behaviourListener.pauseActionByUser();
                break;
            }
            case RESUME_ACTION: {
                behaviourListener.resumeActionByUser();
                break;
            }
            case STOP_ACTION: {
                behaviourListener.stopActionByUser();
                break;
            }
            case RECONNECT_ACTION: {
                reconnect();
                break;
            }
        }
    }

    private void findFastestServerAndConnect() {
        LOGGER.info("findFastestServerAndConnect: state = " + state);
        for (VpnStateListener listener : listeners) {
            listener.onFindingFastestServer();
        }

        if (_fastestServer != null) {
            for (VpnStateListener listener : listeners) {
                listener.notifyServerAsFastest(_fastestServer);
            }
            serversRepository.setCurrentServer(ServerType.ENTRY, _fastestServer);

            connect();
        } else {
            new Handler().postDelayed(this::checkFastestServerAndConnect, 1000);
        }
    }

    private void checkFastestServerAndConnect() {
        Server serverToConnect = _fastestServer != null ?
                _fastestServer : serversRepository.getDefaultServer(ServerType.ENTRY);

        for (VpnStateListener listener : listeners) {
            listener.notifyServerAsFastest(serverToConnect);
        }
        serversRepository.setCurrentServer(ServerType.ENTRY, serverToConnect);

        connect();
    }

    @Override
    public void resume() {
        LOGGER.info("Resume, state = " + state);
        timer.stopTimer();
        setState(CONNECTING);
        updateNotification();
        resumeVpn();
    }

    private void resumeVpn() {
        LOGGER.info("resumeVpn: state = " + state);
        startWireGuard();
    }

    @Override
    public void startConnecting() {
        startConnecting(false);
    }

    public void startConnecting(boolean forceConnecting) {
        LOGGER.info("startConnecting, state = " + state);

        if (!forceConnecting && keyController.isKeysExpired()) {
            keyController.regenerateLiveKeys();
            return;
        }

        System.out.println("RANDOM: isFastestServerEnabled = " + isFastestServerEnabled());
        if (isFastestServerEnabled() && !multiHopController.isEnabled()) {
            findFastestServerAndConnect();
        } else {
            checkRandomServerOptions();
            connect();
        }
    }

    private void connect() {
        LOGGER.info("connect: state = " + state);
        timer.stopTimer();
        startWireGuard();
    }

    private void startWireGuard() {
        LOGGER.info("startWireGuard: state = " + state);
        behaviourListener.onConnectingToVpn();
        setState(CONNECTING);
        updateNotification();
        configManager.startWireGuard();
    }

    private boolean isFastestServerEnabled() {
        return serversRepository.getSettingFastestServer();
    }

    @Override
    public void disconnect() {
        LOGGER.info("Disconnect, state = " + state);
        if (state == CONNECTING || state == CONNECTED) {
            startDisconnectProcess();
            return;
        }
        stopVpn();
    }

    private void startDisconnectProcess() {
        LOGGER.info("startDisconnectProcess: state = " + state);
        setState(DISCONNECTING);
        updateNotification();
        behaviourListener.onDisconnectingFromVpn();
        stopVpn();
    }

    @Override
    public void pause(long pauseDuration) {
        LOGGER.info("Pause, state = " + state);
        timer.startTimer(pauseDuration);
        pauseVpn(pauseDuration);
    }

    private void pauseVpn(long pauseDuration) {
        this.pauseDuration = pauseDuration;
        LOGGER.info("pauseVpn: state = " + state);
        behaviourListener.onDisconnectingFromVpn();
        setState(PAUSING);
        updateNotification(pauseDuration);
        configManager.stopWireGuard();
    }

    @Override
    public void stop() {
        LOGGER.info("Stop, state = " + state);
        timer.stopTimer();
        stopVpn();
        setState(NOT_CONNECTED);
        updateNotification();
    }

    private void stopVpn() {
        LOGGER.info("stopVpn: state = " + state);
        stopWireGuard();
    }

    public void reconnect() {
        LOGGER.info("reconnect: state = " + state);
        setState(CONNECTING);
        updateNotification();
        startConnecting();
    }

    private void stopWireGuard() {
        configManager.stopWireGuard();
    }

    @Override
    public void regenerateKeys() {
        LOGGER.info("regenerateKeys");
        keyController.regenerateLiveKeys();
    }

    private void sendConnectionState() {
        sendConnectionState(0);
    }

    private void sendConnectionState(long pauseDuration) {
        LOGGER.info("sendConnectionState: state = " + state);
        for (VpnStateListener listener : listeners) {
            listener.onConnectionStateChanged(state);
        }
    }

    private void updateNotification() {
        updateNotification(pauseDuration);
    }

    private void updateNotification(long pauseDuration) {
        Context context = IVPNApplication.application;
        Intent intent = new Intent(context, WireGuardUiService.class);
        switch (state) {
            case NOT_CONNECTED:
                if (!WireGuardUiService.isRunning.get()) {
                    return;
                }
                intent.setAction(WIREGUARD_DISCONNECTED);
                break;
            case CONNECTING:
                intent.setAction(WIREGUARD_CONNECTING);
                break;
            case CONNECTED:
                intent.setAction(WIREGUARD_CONNECTED);
                break;
            case PAUSED:
                intent.setAction(WIREGUARD_PAUSED);
                intent.putExtra(VPN_PAUSE_DURATION_EXTRA, pauseDuration);
                break;
            case DISCONNECTING:
            case PAUSING:
                return;
        }
        startService(context, intent);
    }

    private void startService(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void checkRandomServerOptions() {
        if (isRandomEntryServerEnabled()) {
            serversRepository.getRandomServerFor(ServerType.ENTRY, getRandomServerSelectionListener());
        }
        if (isRandomExitServerEnabled()) {
            serversRepository.getRandomServerFor(ServerType.EXIT, getRandomServerSelectionListener());
        }
    }

    private boolean isRandomEntryServerEnabled() {
        return serversRepository.getSettingRandomServer(ServerType.ENTRY);
    }

    private boolean isRandomExitServerEnabled() {
        return serversRepository.getSettingRandomServer(ServerType.EXIT);
    }

    private OnRandomServerSelectionListener getRandomServerSelectionListener() {
        return (server, serverType) -> {
            for (VpnStateListener listener: listeners) {
                listener.notifyServerAsRandom(server, serverType);
            }
        };
    }

    private void setState(ConnectionState state) {
        LOGGER.info("setState, state = " + state + " this = " + this);
        this.state = state;
        sendConnectionState();
    }

    private WireGuardKeysEventsListener getWireGuardKeysEventsListener() {
        return new WireGuardKeysEventsListener() {
            @Override
            public void onKeyGenerating() {
                for (VpnStateListener listener : listeners) {
                    listener.onRegeneratingKeys();
                }
            }

            @Override
            public void onKeyGeneratedSuccess() {
                for (VpnStateListener listener : listeners) {
                    listener.onRegenerationSuccess();
                }
                switch (state) {
                    case NOT_CONNECTED:
                    case PAUSED: {
                        startConnecting();
                        break;
                    }
                    case CONNECTED:
                    case CONNECTING: {
                        reconnect();
                        break;
                    }
                    case DISCONNECTING:
                    case PAUSING: {
                        //ToDo ignore it right now.
                        break;
                    }
                }
            }

            @Override
            public void onKeyGeneratedError(String error, Throwable throwable) {
                switch (state) {
                    case NOT_CONNECTED:
                    case PAUSED: {
                        ErrorResponse errorResponse = Mapper.errorResponseFrom(error);
                        if (errorResponse != null && errorResponse.getStatus() != null) {
                            switch (errorResponse.getStatus()) {
                                case Responses.WIREGUARD_KEY_NOT_FOUND: {
                                    for (VpnStateListener listener : listeners) {
                                        listener.onRegenerationError(Dialogs.WG_UPGRADE_ERROR);
                                    }
                                    return;
                                }
                                case Responses.WIREGUARD_KEY_LIMIT_REACHED: {
                                    for (VpnStateListener listener : listeners) {
                                        listener.onRegenerationError(Dialogs.WG_MAXIMUM_KEYS_REACHED);
                                    }
                                    return;
                                }
                            }
                        }

                        if (keyController.isKeysHardExpired()) {
                            for (VpnStateListener listener : listeners) {
                                listener.onRegenerationError(Dialogs.WG_UPGRADE_ERROR);
                            }
                        } else {
                            WireGuardBehavior.this.keyController.startShortPeriodAlarm();
                            startConnecting(true);
                        }
                        break;
                    }
                    case CONNECTED:
                    case CONNECTING:
                    case DISCONNECTING:
                    case PAUSING: {
                        //There is nothing to do;
                        break;
                    }
                }
            }
        };
    }

    @Override
    public void onStateChanged(@NotNull Tunnel.State newState) {
        if (newState == Tunnel.State.UP) {
            setState(CONNECTED);
            behaviourListener.updateVpnConnectionState(VPNConnectionState.CONNECTED);
        } else {
            if (state == PAUSING) {
                setState(PAUSED);
            } else {
                setState(NOT_CONNECTED);
            }
            sendConnectionState();
            for (VpnStateListener listener : listeners) {
                listener.onCheckSessionState();
            }
            behaviourListener.updateVpnConnectionState(VPNConnectionState.DISCONNECTED);
        }

        updateNotification();
    }
}