/**
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.settings.applications;

import com.android.internal.content.PackageHelper;
import com.android.settings.R;
import com.android.settings.applications.ApplicationsState.AppEntry;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.usb.IUsbManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import android.content.ComponentName;
import android.view.View;
import android.widget.AppSecurityEditablePermissions;
import android.widget.AppSecurityPermissions;
import android.widget.AppSecurityPermissionsBase;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Activity to display application information from Settings. This activity presents
 * extended information associated with a package like code, data, total size, permissions
 * used by the application and also the set of default launchable activities.
 * For system applications, an option to clear user data is displayed only if data size is > 0.
 * System applications that do not want clear user data do not have this option.
 * For non-system applications, there is no option to clear data. Instead there is an option to
 * uninstall the application.
 */
public class InstalledAppDetails extends Activity
        implements View.OnClickListener, ApplicationsState.Callbacks {
    private static final String TAG="InstalledAppDetails";
    static final boolean SUPPORT_DISABLE_APPS = false;
    private static final boolean localLOGV = false;
    
    private PackageManager mPm;
    private IUsbManager mUsbManager;
    private ApplicationsState mState;
    private ApplicationsState.AppEntry mAppEntry;
    private PackageInfo mPackageInfo;
    private CanBeOnSdCardChecker mCanBeOnSdCardChecker;
    private Button mUninstallButton;
    private boolean mMoveInProgress = false;
    private boolean mUpdatedSysApp = false;
    private Button mActivitiesButton;
    private boolean mCanClearData = true;
    private TextView mAppVersion;
    private TextView mTotalSize;
    private TextView mAppSize;
    private TextView mDataSize;
    private ClearUserDataObserver mClearDataObserver;
    // Views related to cache info
    private TextView mCacheSize;
    private Button mClearCacheButton;
    private ClearCacheObserver mClearCacheObserver;
    private Button mForceStopButton;
    private Button mClearDataButton;
    private Button mMoveAppButton;
    private int mMoveErrorCode;
    
    private PackageMoveObserver mPackageMoveObserver;
    
    private boolean mHaveSizes = false;
    private long mLastCodeSize = -1;
    private long mLastDataSize = -1;
    private long mLastCacheSize = -1;
    private long mLastTotalSize = -1;
    
    //internal constants used in Handler
    private static final int OP_SUCCESSFUL = 1;
    private static final int OP_FAILED = 2;
    private static final int CLEAR_USER_DATA = 1;
    private static final int CLEAR_CACHE = 3;
    private static final int PACKAGE_MOVE = 4;
    
    // invalid size value used initially and also when size retrieval through PackageManager
    // fails for whatever reason
    private static final int SIZE_INVALID = -1;
    
    // Resource strings
    private CharSequence mInvalidSizeStr;
    private CharSequence mComputingStr;
    
    // Dialog identifiers used in showDialog
    private static final int DLG_BASE = 0;
    private static final int DLG_CLEAR_DATA = DLG_BASE + 1;
    private static final int DLG_FACTORY_RESET = DLG_BASE + 2;
    private static final int DLG_APP_NOT_FOUND = DLG_BASE + 3;
    private static final int DLG_CANNOT_CLEAR_DATA = DLG_BASE + 4;
    private static final int DLG_FORCE_STOP = DLG_BASE + 5;
    private static final int DLG_MOVE_FAILED = DLG_BASE + 6;
    
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            // If the activity is gone, don't process any more messages.
            if (isFinishing()) {
                return;
            }
            switch (msg.what) {
                case CLEAR_USER_DATA:
                    processClearMsg(msg);
                    break;
                case CLEAR_CACHE:
                    // Refresh size info
                    mState.requestSize(mAppEntry.info.packageName);
                    break;
                case PACKAGE_MOVE:
                    processMoveMsg(msg);
                    break;
                default:
                    break;
            }
        }
    };
    
    class ClearUserDataObserver extends IPackageDataObserver.Stub {
       public void onRemoveCompleted(final String packageName, final boolean succeeded) {
           final Message msg = mHandler.obtainMessage(CLEAR_USER_DATA);
           msg.arg1 = succeeded?OP_SUCCESSFUL:OP_FAILED;
           mHandler.sendMessage(msg);
        }
    }
    
    class ClearCacheObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(final String packageName, final boolean succeeded) {
            final Message msg = mHandler.obtainMessage(CLEAR_CACHE);
            msg.arg1 = succeeded ? OP_SUCCESSFUL:OP_FAILED;
            mHandler.sendMessage(msg);
         }
     }

    class PackageMoveObserver extends IPackageMoveObserver.Stub {
        public void packageMoved(String packageName, int returnCode) throws RemoteException {
            final Message msg = mHandler.obtainMessage(PACKAGE_MOVE);
            msg.arg1 = returnCode;
            mHandler.sendMessage(msg);
        }
    }
    
    private String getSizeStr(long size) {
        if (size == SIZE_INVALID) {
            return mInvalidSizeStr.toString();
        }
        return Formatter.formatFileSize(this, size);
    }
    
    private void initDataButtons() {
        if ((mAppEntry.info.flags&(ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA))
                == ApplicationInfo.FLAG_SYSTEM) {
            mClearDataButton.setText(R.string.clear_user_data_text);
            mClearDataButton.setEnabled(false);
            mCanClearData = false;
        } else {
            if (mAppEntry.info.manageSpaceActivityName != null) {
                mClearDataButton.setText(R.string.manage_space_text);
            } else {
                mClearDataButton.setText(R.string.clear_user_data_text);
            }
            mClearDataButton.setOnClickListener(this);
        }
    }

    private CharSequence getMoveErrMsg(int errCode) {
        switch (errCode) {
            case PackageManager.MOVE_FAILED_INSUFFICIENT_STORAGE:
                return getString(R.string.insufficient_storage);
            case PackageManager.MOVE_FAILED_DOESNT_EXIST:
                return getString(R.string.does_not_exist);
            case PackageManager.MOVE_FAILED_FORWARD_LOCKED:
                return getString(R.string.app_forward_locked);
            case PackageManager.MOVE_FAILED_INVALID_LOCATION:
                return getString(R.string.invalid_location);
            case PackageManager.MOVE_FAILED_SYSTEM_PACKAGE:
                return getString(R.string.system_package);
            case PackageManager.MOVE_FAILED_INTERNAL_ERROR:
                return "";
        }
        return "";
    }

    private void initMoveButton() {
        boolean dataOnly = false;
        dataOnly = (mPackageInfo == null) && (mAppEntry != null);
        boolean moveDisable = true;

        if (dataOnly) {
            mMoveAppButton.setText(R.string.move_app);
        } else if ((mAppEntry.info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
            mMoveAppButton.setText(R.string.move_app_to_internal);
            // Always let apps move to internal storage from sdcard.
            moveDisable = false;
        } else {
            mMoveAppButton.setText(R.string.move_app_to_sdcard);
            mCanBeOnSdCardChecker.init();
            moveDisable = !mCanBeOnSdCardChecker.check(mAppEntry.info);
        }
        boolean allowMoveAllApps = android.provider.Settings.Secure.getInt(getContentResolver(),
                 android.provider.Settings.Secure.ALLOW_MOVE_ALL_APPS_EXTERNAL, 1) == 1;
        if (!allowMoveAllApps && moveDisable) {
            mMoveAppButton.setEnabled(false);
        } else {
            mMoveAppButton.setOnClickListener(this);
            mMoveAppButton.setEnabled(true);
        }
    }

    private void initUninstallButtons() {
        mUpdatedSysApp = (mAppEntry.info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        boolean enabled = true;
        if (mUpdatedSysApp) {
            mUninstallButton.setText(R.string.app_factory_reset);
        } else {
            if ((mAppEntry.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                enabled = false;
                if (SUPPORT_DISABLE_APPS) {
                    try {
                        // Try to prevent the user from bricking their phone
                        // by not allowing disabling of apps signed with the
                        // system cert and any launcher app in the system.
                        PackageInfo sys = mPm.getPackageInfo("android",
                                PackageManager.GET_SIGNATURES);
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_HOME);
                        intent.setPackage(mAppEntry.info.packageName);
                        List<ResolveInfo> homes = mPm.queryIntentActivities(intent, 0);
                        if ((homes != null && homes.size() > 0) ||
                                (mPackageInfo != null &&
                                        sys.signatures[0].equals(mPackageInfo.signatures[0]))) {
                            // Disable button for core system applications.
                            mUninstallButton.setText(R.string.disable_text);
                        } else if (mAppEntry.info.enabled) {
                            mUninstallButton.setText(R.string.disable_text);
                            enabled = true;
                        } else {
                            mUninstallButton.setText(R.string.enable_text);
                            enabled = true;
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "Unable to get package info", e);
                    }
                }
            } else {
                mUninstallButton.setText(R.string.uninstall_text);
            }
        }
        mUninstallButton.setEnabled(enabled);
        if (enabled) {
            // Register listener
            mUninstallButton.setOnClickListener(this);
        }
    }

    /** Called when the activity is first created. */
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        mState = ApplicationsState.getInstance(getApplication());
        mPm = getPackageManager();
        IBinder b = ServiceManager.getService(Context.USB_SERVICE);
        mUsbManager = IUsbManager.Stub.asInterface(b);

        mCanBeOnSdCardChecker = new CanBeOnSdCardChecker();
        
        setContentView(R.layout.installed_app_details);
        
        mComputingStr = getText(R.string.computing_size);
        
        // Set default values on sizes
        mTotalSize = (TextView)findViewById(R.id.total_size_text);
        mAppSize = (TextView)findViewById(R.id.application_size_text);
        mDataSize = (TextView)findViewById(R.id.data_size_text);
        
        // Get Control button panel
        View btnPanel = findViewById(R.id.control_buttons_panel);
        mForceStopButton = (Button) btnPanel.findViewById(R.id.left_button);
        mForceStopButton.setText(R.string.force_stop);
        mUninstallButton = (Button)btnPanel.findViewById(R.id.right_button);
        mForceStopButton.setEnabled(false);
        
        // Initialize clear data and move install location buttons
        View data_buttons_panel = findViewById(R.id.data_buttons_panel);
        mClearDataButton = (Button) data_buttons_panel.findViewById(R.id.left_button);
        mMoveAppButton = (Button) data_buttons_panel.findViewById(R.id.right_button);
        
        // Cache section
        mCacheSize = (TextView) findViewById(R.id.cache_size_text);
        mClearCacheButton = (Button) findViewById(R.id.clear_cache_button);
        
        mActivitiesButton = (Button)findViewById(R.id.clear_activities_button);
    }

    // Utility method to set applicaiton label and icon.
    private void setAppLabelAndIcon(PackageInfo pkgInfo) {
        View appSnippet = findViewById(R.id.app_snippet);
        ImageView icon = (ImageView) appSnippet.findViewById(R.id.app_icon);
        mState.ensureIcon(mAppEntry);
        icon.setImageDrawable(mAppEntry.icon);
        // Set application name.
        TextView label = (TextView) appSnippet.findViewById(R.id.app_name);
        label.setText(mAppEntry.label);
        // Version number of application
        mAppVersion = (TextView) appSnippet.findViewById(R.id.app_size);

        if (pkgInfo != null && pkgInfo.versionName != null) {
            mAppVersion.setVisibility(View.VISIBLE);
            mAppVersion.setText(getString(R.string.version_text,
                    String.valueOf(pkgInfo.versionName)));
        } else {
            mAppVersion.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        
        mState.resume(this);
        if (!refreshUi()) {
            setIntentAndFinish(true, true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mState.pause();
    }

    @Override
    public void onAllSizesComputed() {
    }

    @Override
    public void onPackageIconChanged() {
    }

    @Override
    public void onPackageListChanged() {
        refreshUi();
    }

    @Override
    public void onRebuildComplete(ArrayList<AppEntry> apps) {
    }

    @Override
    public void onPackageSizeChanged(String packageName) {
        if (packageName.equals(mAppEntry.info.packageName)) {
            refreshSizeInfo();
        }
    }

    @Override
    public void onRunningStateChanged(boolean running) {
    }

    private boolean refreshUi() {
        if (mMoveInProgress) {
            return true;
        }
        
        Intent intent = getIntent();
        final String packageName = intent.getData().getSchemeSpecificPart();
        mAppEntry = mState.getEntry(packageName);
        
        if (mAppEntry == null) {
            return false; // onCreate must have failed, make sure to exit
        }
        
        // Get application info again to refresh changed properties of application
        try {
            mPackageInfo = mPm.getPackageInfo(mAppEntry.info.packageName,
                    PackageManager.GET_DISABLED_COMPONENTS |
                    PackageManager.GET_UNINSTALLED_PACKAGES |
                    PackageManager.GET_SIGNATURES);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Exception when retrieving package:" + mAppEntry.info.packageName, e);
            return false; // onCreate must have failed, make sure to exit
        }
        
        // Get list of preferred activities
        List<ComponentName> prefActList = new ArrayList<ComponentName>();
        
        // Intent list cannot be null. so pass empty list
        List<IntentFilter> intentList = new ArrayList<IntentFilter>();
        mPm.getPreferredActivities(intentList, prefActList, packageName);
        if(localLOGV) Log.i(TAG, "Have "+prefActList.size()+" number of activities in prefered list");
        boolean hasUsbDefaults = false;
        try {
            hasUsbDefaults = mUsbManager.hasDefaults(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "mUsbManager.hasDefaults", e);
        }
        TextView autoLaunchView = (TextView)findViewById(R.id.auto_launch);
        if (prefActList.size() <= 0 && !hasUsbDefaults) {
            // Disable clear activities button
            autoLaunchView.setText(R.string.auto_launch_disable_text);
            mActivitiesButton.setEnabled(false);
        } else {
            autoLaunchView.setText(R.string.auto_launch_enable_text);
            mActivitiesButton.setEnabled(true);
            mActivitiesButton.setOnClickListener(this);
        }

        // Security permissions section
        LinearLayout permsView = (LinearLayout) findViewById(R.id.permissions_section);
        AppSecurityPermissionsBase asp = null;
        if (isRevokeEnabled() && (mAppEntry.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            asp = new AppSecurityEditablePermissions(this, packageName);
        } else {
            asp = new AppSecurityPermissions(this, packageName);
        }
        if (asp.getPermissionCount() > 0) {
            permsView.setVisibility(View.VISIBLE);
            // Make the security sections header visible
            LinearLayout securityList = (LinearLayout) permsView.findViewById(
                    R.id.security_settings_list);
            securityList.removeAllViews();
            securityList.addView(asp.getPermissionsView());
        } else {
            permsView.setVisibility(View.GONE);
        }
        
        checkForceStop();
        setAppLabelAndIcon(mPackageInfo);
        refreshButtons();
        refreshSizeInfo();
        return true;
    }

    private boolean isRevokeEnabled() {
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ENABLE_PERMISSIONS_MANAGEMENT,
                getResources().getBoolean(com.android.internal.R.bool.config_enablePermissionsManagement) ? 1 : 0) == 1;
    }
    
    private void setIntentAndFinish(boolean finish, boolean appChanged) {
        if(localLOGV) Log.i(TAG, "appChanged="+appChanged);
        Intent intent = new Intent();
        intent.putExtra(ManageApplications.APP_CHG, appChanged);
        setResult(ManageApplications.RESULT_OK, intent);
        if(finish) {
            finish();
        }
    }
    
    private void refreshSizeInfo() {
        if (mAppEntry.size == ApplicationsState.SIZE_INVALID
                || mAppEntry.size == ApplicationsState.SIZE_UNKNOWN) {
            mLastCodeSize = mLastDataSize = mLastCacheSize = mLastTotalSize = -1;
            if (!mHaveSizes) {
                mAppSize.setText(mComputingStr);
                mDataSize.setText(mComputingStr);
                mCacheSize.setText(mComputingStr);
                mTotalSize.setText(mComputingStr);
            }
            mClearDataButton.setEnabled(false);
            mClearCacheButton.setEnabled(false);
            
        } else {
            mHaveSizes = true;
            if (mLastCodeSize != mAppEntry.codeSize) {
                mLastCodeSize = mAppEntry.codeSize;
                mAppSize.setText(getSizeStr(mAppEntry.codeSize));
            }
            if (mLastDataSize != mAppEntry.dataSize) {
                mLastDataSize = mAppEntry.dataSize;
                mDataSize.setText(getSizeStr(mAppEntry.dataSize));
            }
            if (mLastCacheSize != mAppEntry.cacheSize) {
                mLastCacheSize = mAppEntry.cacheSize;
                mCacheSize.setText(getSizeStr(mAppEntry.cacheSize));
            }
            if (mLastTotalSize != mAppEntry.size) {
                mLastTotalSize = mAppEntry.size;
                mTotalSize.setText(getSizeStr(mAppEntry.size));
            }
            
            if (mAppEntry.dataSize <= 0 || !mCanClearData) {
                mClearDataButton.setEnabled(false);
            } else {
                mClearDataButton.setEnabled(true);
                mClearDataButton.setOnClickListener(this);
            }
            if (mAppEntry.cacheSize <= 0) {
                mClearCacheButton.setEnabled(false);
            } else {
                mClearCacheButton.setEnabled(true);
                mClearCacheButton.setOnClickListener(this);
            }
        }
    }
    
    /*
     * Private method to handle clear message notification from observer when
     * the async operation from PackageManager is complete
     */
    private void processClearMsg(Message msg) {
        int result = msg.arg1;
        String packageName = mAppEntry.info.packageName;
        mClearDataButton.setText(R.string.clear_user_data_text);
        if(result == OP_SUCCESSFUL) {
            Log.i(TAG, "Cleared user data for package : "+packageName);
            mState.requestSize(mAppEntry.info.packageName);
        } else {
            mClearDataButton.setEnabled(true);
        }
        checkForceStop();
    }

    private void refreshButtons() {
        if (!mMoveInProgress) {
            initUninstallButtons();
            initDataButtons();
            initMoveButton();
        } else {
            mMoveAppButton.setText(R.string.moving);
            mMoveAppButton.setEnabled(false);
            mUninstallButton.setEnabled(false);
        }
    }

    private void processMoveMsg(Message msg) {
        int result = msg.arg1;
        String packageName = mAppEntry.info.packageName;
        // Refresh the button attributes.
        mMoveInProgress = false;
        if (result == PackageManager.MOVE_SUCCEEDED) {
            Log.i(TAG, "Moved resources for " + packageName);
            // Refresh size information again.
            mState.requestSize(mAppEntry.info.packageName);
        } else {
            mMoveErrorCode = result;
            showDialogInner(DLG_MOVE_FAILED);
        }
        refreshUi();
    }

    /*
     * Private method to initiate clearing user data when the user clicks the clear data 
     * button for a system package
     */
    private  void initiateClearUserData() {
        mClearDataButton.setEnabled(false);
        // Invoke uninstall or clear user data based on sysPackage
        String packageName = mAppEntry.info.packageName;
        Log.i(TAG, "Clearing user data for package : " + packageName);
        if (mClearDataObserver == null) {
            mClearDataObserver = new ClearUserDataObserver();
        }
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        boolean res = am.clearApplicationUserData(packageName, mClearDataObserver);
        if (!res) {
            // Clearing data failed for some obscure reason. Just log error for now
            Log.i(TAG, "Couldnt clear application user data for package:"+packageName);
            showDialogInner(DLG_CANNOT_CLEAR_DATA);
        } else {
            mClearDataButton.setText(R.string.recompute_size);
        }
    }
    
    private void showDialogInner(int id) {
        //removeDialog(id);
        showDialog(id);
    }
    
    @Override
    public Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
        case DLG_CLEAR_DATA:
            return new AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_data_dlg_title))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.clear_data_dlg_text))
            .setPositiveButton(R.string.dlg_ok,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    // Clear user data here
                    initiateClearUserData();
                }
            })
            .setNegativeButton(R.string.dlg_cancel, null)
            .create();
        case DLG_FACTORY_RESET:
            return new AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_factory_reset_dlg_title))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.app_factory_reset_dlg_text))
            .setPositiveButton(R.string.dlg_ok,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    // Clear user data here
                    uninstallPkg(mAppEntry.info.packageName);
                }
            })
            .setNegativeButton(R.string.dlg_cancel, null)
            .create();
        case DLG_APP_NOT_FOUND:
            return new AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_not_found_dlg_title))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.app_not_found_dlg_title))
            .setNeutralButton(getString(R.string.dlg_ok),
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    //force to recompute changed value
                    setIntentAndFinish(true, true);
                }
            })
            .create();
        case DLG_CANNOT_CLEAR_DATA:
            return new AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_failed_dlg_title))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.clear_failed_dlg_text))
            .setNeutralButton(R.string.dlg_ok,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    mClearDataButton.setEnabled(false);
                    //force to recompute changed value
                    setIntentAndFinish(false, false);
                }
            })
            .create();
        case DLG_FORCE_STOP:
            return new AlertDialog.Builder(this)
            .setTitle(getString(R.string.force_stop_dlg_title))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.force_stop_dlg_text))
            .setPositiveButton(R.string.dlg_ok,
                new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    // Force stop
                    forceStopPackage(mAppEntry.info.packageName);
                }
            })
            .setNegativeButton(R.string.dlg_cancel, null)
            .create();
        case DLG_MOVE_FAILED:
            CharSequence msg = getString(R.string.move_app_failed_dlg_text,
                    getMoveErrMsg(mMoveErrorCode));
            return new AlertDialog.Builder(this)
            .setTitle(getString(R.string.move_app_failed_dlg_title))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(msg)
            .setNeutralButton(R.string.dlg_ok, null)
            .create();
        }
        return null;
    }

    private void uninstallPkg(String packageName) {
         // Create new intent to launch Uninstaller activity
        Uri packageURI = Uri.parse("package:"+packageName);
        Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, packageURI);
        startActivity(uninstallIntent);
        setIntentAndFinish(true, true);
    }

    private void forceStopPackage(String pkgName) {
        ActivityManager am = (ActivityManager)getSystemService(
                Context.ACTIVITY_SERVICE);
        am.forceStopPackage(pkgName);
        checkForceStop();
    }

    private final BroadcastReceiver mCheckKillProcessesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mForceStopButton.setEnabled(getResultCode() != RESULT_CANCELED);
            mForceStopButton.setOnClickListener(InstalledAppDetails.this);
        }
    };
    
    private void checkForceStop() {
        Intent intent = new Intent(Intent.ACTION_QUERY_PACKAGE_RESTART,
                Uri.fromParts("package", mAppEntry.info.packageName, null));
        intent.putExtra(Intent.EXTRA_PACKAGES, new String[] { mAppEntry.info.packageName });
        intent.putExtra(Intent.EXTRA_UID, mAppEntry.info.uid);
        sendOrderedBroadcast(intent, null, mCheckKillProcessesReceiver, null,
                Activity.RESULT_CANCELED, null, null);
    }
    
    static class DisableChanger extends AsyncTask<Object, Object, Object> {
        final PackageManager mPm;
        final WeakReference<InstalledAppDetails> mActivity;
        final ApplicationInfo mInfo;
        final int mState;

        DisableChanger(InstalledAppDetails activity, ApplicationInfo info, int state) {
            mPm = activity.mPm;
            mActivity = new WeakReference<InstalledAppDetails>(activity);
            mInfo = info;
            mState = state;
        }

        @Override
        protected Object doInBackground(Object... params) {
            mPm.setApplicationEnabledSetting(mInfo.packageName, mState, 0);
            return null;
        }
    }

    /*
     * Method implementing functionality of buttons clicked
     * @see android.view.View.OnClickListener#onClick(android.view.View)
     */
    public void onClick(View v) {
        String packageName = mAppEntry.info.packageName;
        if(v == mUninstallButton) {
            if (mUpdatedSysApp) {
                showDialogInner(DLG_FACTORY_RESET);
            } else {
                if ((mAppEntry.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    new DisableChanger(this, mAppEntry.info, mAppEntry.info.enabled ?
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT).execute((Object)null);
                } else {
                    uninstallPkg(packageName);
                }
            }
        } else if(v == mActivitiesButton) {
            mPm.clearPackagePreferredActivities(packageName);
            try {
                mUsbManager.clearDefaults(packageName);
            } catch (RemoteException e) {
                Log.e(TAG, "mUsbManager.clearDefaults", e);
            }
            mActivitiesButton.setEnabled(false);
        } else if(v == mClearDataButton) {
            if (mAppEntry.info.manageSpaceActivityName != null) {
                Intent intent = new Intent(Intent.ACTION_DEFAULT);
                intent.setClassName(mAppEntry.info.packageName,
                        mAppEntry.info.manageSpaceActivityName);
                startActivityForResult(intent, -1);
            } else {
                showDialogInner(DLG_CLEAR_DATA);
            }
        } else if (v == mClearCacheButton) {
            // Lazy initialization of observer
            if (mClearCacheObserver == null) {
                mClearCacheObserver = new ClearCacheObserver();
            }
            mPm.deleteApplicationCacheFiles(packageName, mClearCacheObserver);
        } else if (v == mForceStopButton) {
            showDialogInner(DLG_FORCE_STOP);
            //forceStopPackage(mAppInfo.packageName);
        } else if (v == mMoveAppButton) {
            if (mPackageMoveObserver == null) {
                mPackageMoveObserver = new PackageMoveObserver();
            }
            int moveFlags = (mAppEntry.info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0 ?
                    PackageManager.MOVE_INTERNAL : PackageManager.MOVE_EXTERNAL_MEDIA;
            mMoveInProgress = true;
            refreshButtons();
            mPm.movePackage(mAppEntry.info.packageName, mPackageMoveObserver, moveFlags);
        }
    }
}

