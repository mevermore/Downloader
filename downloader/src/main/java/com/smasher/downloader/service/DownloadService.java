package com.smasher.downloader.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.util.Log;


import com.smasher.downloader.DownloadConfig;
import com.smasher.downloader.R;
import com.smasher.downloader.entity.DownloadInfo;
import com.smasher.downloader.entity.RequestInfo;
import com.smasher.downloader.handler.WeakReferenceHandler;
import com.smasher.downloader.manager.IconManager;
import com.smasher.downloader.manager.NotifyManager;
import com.smasher.downloader.task.DownLoadTask;
import com.smasher.downloader.thread.ThreadPool;
import com.smasher.downloader.util.DownloadUtils;
import com.smasher.downloader.util.NetworkUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * @author matao
 * @date 2019/4/24
 */
public class DownloadService extends Service implements Handler.Callback {

    public static final String SERVICE_INTENT_EXTRA = "service_intent_extra";

    private static final String TAG = "[DL]DownloadService";

    private static boolean canRequest = true;
    private static boolean showTip = true;

    /**
     * key-url value-DownLoadTask
     */
    private HashMap<String, DownLoadTask> tasks = new HashMap<>();

    private WeakReferenceHandler mHandler;

    private String environmentNotWifi;


    @Override
    public void onCreate() {
        super.onCreate();
        environmentNotWifi = getString(R.string.download_tip_un_wifi);
        mHandler = new WeakReferenceHandler(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    /**
     * @param intent  intent
     * @param flags   flags
     * @param startId startId 标识
     * @return int
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (canRequest) {
            Log.i(TAG, "onStartCommand:-> 启动了service服务 intent=" + intent + "\t this=" + this);
            canRequest = false;
            if (intent != null) {
                if (intent.hasExtra(SERVICE_INTENT_EXTRA)) {
                    try {
                        ArrayList<RequestInfo> arrayListExtra = intent.getParcelableArrayListExtra(SERVICE_INTENT_EXTRA);
                        if (arrayListExtra != null && arrayListExtra.size() > 0) {
                            for (RequestInfo request : arrayListExtra) {
                                dispatch(request);
                            }
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "onStartCommand()-> 接受数据,启动线程中发生异常");
                        e.printStackTrace();
                    }
                }
            }
            canRequest = true;
        }


        return super.onStartCommand(intent, flags, startId);
    }


    @Override
    public boolean stopService(Intent name) {
        return super.stopService(name);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void dispatch(RequestInfo request) {

        DownloadInfo info = request.getDownloadInfo();
        int requestType = request.getCommand();

        switch (requestType) {
            case RequestInfo.COMMAND_DOWNLOAD:
            case RequestInfo.COMMAND_PAUSE:
                actionTask(request);
                break;
            case RequestInfo.COMMAND_INSTALL:
                install(info);
                break;
            case RequestInfo.COMMAND_OPEN:
                openGame(info);
                break;
            default:
                break;
        }

    }


    /**
     * 执行暂停、下载操作
     *
     * @param request request
     */
    private void actionTask(RequestInfo request) {

        DownLoadTask task;
        DownloadInfo info = request.getDownloadInfo();
        int requestType = request.getCommand();
        IconManager.getSingleton().loadIcon(info.getIconUrl());

        boolean contains = tasks.containsKey(info.getUrl());
        if (!contains) {
            //任务列表中没有
            if (info.getId() <= 0) {
                info.setId(tasks.size() + 1000);
            }
            task = new DownLoadTask(info, mHandler);
            tasks.put(info.getUrl(), task);
        } else {
            //任务列表中有
            task = tasks.get(info.getUrl());
        }

        if (task == null) {
            Log.e(TAG, "executeDownload: task is empty");
            return;
        }

        if (requestType == RequestInfo.COMMAND_DOWNLOAD) {
            info.setStatus(DownloadInfo.JS_STATE_WAIT);

            if (!executeTip(info)) {
                executeDownload(task);
            }
        } else {
            task.stop();
        }
        executeNotification(info);
    }


    /**
     * 安装操作
     *
     * @param downloadInfo 下载信息
     */
    private void install(DownloadInfo downloadInfo) {
        try {
            if (DownloadUtils.getFileSize(downloadInfo) > 0) {
                DownloadUtils.install(this, downloadInfo);
            } else {
                Log.d(TAG, "install: error");
            }
        } catch (Exception e) {
            Log.e(TAG, "install: exception", e);
        }

    }


    /**
     * 打开游戏
     *
     * @param downloadInfo 下载信息
     */
    public void openGame(DownloadInfo downloadInfo) {
        try {
            if (isInstalled(downloadInfo)) {
                PackageManager packageManager = getPackageManager();
                Intent intent = packageManager.getLaunchIntentForPackage(downloadInfo.getUniqueKey());
                if (intent != null) {
                    startActivity(intent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "openGame: exception", e);
        }
    }


    /**
     * @param downloadInfo 下载信息
     * @return 是否已经安装
     */
    private boolean isInstalled(DownloadInfo downloadInfo) {//,String packageName
        try {
            PackageManager packageManager = getPackageManager();
            List<PackageInfo> packages = packageManager.getInstalledPackages(0);
            if (packages != null) {
                for (int i = 0; i < packages.size(); i++) {
                    PackageInfo packageInfo = packages.get(i);
                    if (packageInfo != null && packageInfo.packageName.equals(downloadInfo.getUniqueKey())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    private void executeDownload(DownLoadTask task) {
        ThreadPool.getInstance(ThreadPool.PRIORITY_GAME_DOWNLOAD).execute(task);
    }

    private void executeNotification(DownloadInfo info) {
        updateNotification(this, info);
    }

    private boolean executeTip(DownloadInfo info) {
        boolean wifi = NetworkUtil.isWifiAvailable(this);
        if (!wifi && showTip) {
            info.setStatus(DownloadInfo.JS_STATE_PAUSE);
            //首次提示
            showTip = false;

            sendToast(environmentNotWifi);
            return true;
        }
        return false;
    }


    private void updateNotification(Context context, DownloadInfo info) {
        Bitmap icon = IconManager.getSingleton().getIcon(info.getIconUrl());
        NotifyManager.getSingleton(this).updateNotification(context, info, icon);
    }


    private void sendToast(String message) {
        String action = DownloadConfig.DOWNLOAD_ACTION_TOAST;
        Intent intent = new Intent();
        intent.setAction(action);
        intent.putExtra(action, message);
        sendBroadcast(intent);
    }


    @Override
    public boolean handleMessage(Message msg) {
        DownloadInfo downloadInfo = (DownloadInfo) msg.obj;
        int what = msg.what;
        switch (what) {
            case DownloadInfo.JS_STATE_NORMAL:
                //do nothing
                break;
            case DownloadInfo.JS_STATE_WAIT:
                executeNotification(downloadInfo);
                break;
            case DownloadInfo.JS_STATE_DOWNLOAD_PRE:
                executeNotification(downloadInfo);
                break;
            case DownloadInfo.JS_STATE_GET_TOTAL:
                executeSaveLength(downloadInfo);
                break;
            case DownloadInfo.JS_STATE_DOWNLOADING:
                executeNotification(downloadInfo);
                break;
            case DownloadInfo.JS_STATE_PAUSE:
                executeNotification(downloadInfo);
                break;
            case DownloadInfo.JS_STATE_FINISH:
                executeNotification(downloadInfo);
                if (needInstall(downloadInfo)) {
                    install(downloadInfo);
                }
                break;
            case DownloadInfo.JS_STATE_FAILED:
                executeNotification(downloadInfo);
                break;
            case DownloadInfo.JS_STATE_INSTALLED:
                break;
            default:
                break;
        }
        return true;
    }

    private boolean needInstall(DownloadInfo downloadInfo) {
        return downloadInfo.getDownLoadType() == DownloadInfo.DOWN_LOAD_TYPE_APK;
    }


    private void executeSaveLength(DownloadInfo downloadInfo) {
        SharedPreferences mSharedPreferences = getSharedPreferences("DownLoadInfo", Context.MODE_PRIVATE);
        //保存key-value对到文件中
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putLong(downloadInfo.getFullName(), downloadInfo.getTotal());
        editor.apply();
    }
}
