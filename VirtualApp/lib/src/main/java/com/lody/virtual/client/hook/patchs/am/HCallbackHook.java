package com.lody.virtual.client.hook.patchs.am;

import java.lang.reflect.Field;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.interfaces.Injectable;
import com.lody.virtual.helper.ExtraConstants;
import com.lody.virtual.helper.compat.ActivityRecordCompat;
import com.lody.virtual.helper.compat.ClassLoaderCompat;
import com.lody.virtual.helper.proto.AppInfo;
import com.lody.virtual.helper.utils.XLog;

import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Message;

/**
 * @author Lody
 *
 *         <p>
 *         注入我们的Callback到
 *         <h1>ActivityThread$H</h1>.
 * @see Handler.Callback
 * @see ActivityThread
 */
public class HCallbackHook implements Handler.Callback, Injectable {

	////////////////////////////////////////////////////////////////
	////////////////// Copy from ActivityThread$H////////////////////
	////////////////////////////////////////////////////////////////
	public static final int LAUNCH_ACTIVITY = 100;

	private static final String TAG = HCallbackHook.class.getSimpleName();
	private static final HCallbackHook sCallback = new HCallbackHook();
	private static Field f_h;
	private static Field f_handleCallback;

	static {
		try {
			f_h = ActivityThread.class.getDeclaredField("mH");
			f_handleCallback = Handler.class.getDeclaredField("mCallback");
			f_h.setAccessible(true);
			f_handleCallback.setAccessible(true);
		} catch (NoSuchFieldException e) {
			// Ignore
		}
	}

	/**
	 * 其它插件化可能也会注入Activity$H, 这里要保留其它插件化的Callback引用，我们的Callback完事后再调用它的。
	 */
	private Handler.Callback otherCallback;

	private HCallbackHook() {
	}

	public static HCallbackHook getDefault() {
		return sCallback;
	}

	public static Handler getH() {
		try {
			return (Handler) f_h.get(VirtualCore.mainThread());
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return null;
	}

	private static Handler.Callback getHCallback() {
		try {
			Handler handler = getH();

			return (Handler.Callback) f_handleCallback.get(handler);
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public boolean handleMessage(Message msg) {
		switch (msg.what) {
			case LAUNCH_ACTIVITY : {
				if (!handleLaunchActivity(msg)) {
					return true;
				}
				break;
			}
		}
		if (true) {
			return false;
		}
		// 向下调用兼容其它的插件化
		return otherCallback != null && otherCallback.handleMessage(msg);
	}

	private boolean handleLaunchActivity(Message msg) {
		Object r = msg.obj;
		// StubIntent

		Intent stubIntent = ActivityRecordCompat.getIntent(r);

		// TargetIntent
		Intent targetIntent = stubIntent.getParcelableExtra(ExtraConstants.EXTRA_TARGET_INTENT);

		ComponentName component = targetIntent.getComponent();
		String pkgName = component.getPackageName();

		// 匹配插件
		AppInfo appInfo = VirtualCore.getCore().findApp(pkgName);

		if (appInfo == null) {
			return false;
		}
		ClassLoader pluginClassLoader = appInfo.getClassLoader();
		stubIntent.setExtrasClassLoader(pluginClassLoader);
		targetIntent.setExtrasClassLoader(pluginClassLoader);

		// StubActivityInfo
		ActivityInfo stubActInfo = stubIntent.getParcelableExtra(ExtraConstants.EXTRA_STUB_ACT_INFO);
		// TargetActivityInfo
		ActivityInfo targetActInfo = stubIntent.getParcelableExtra(ExtraConstants.EXTRA_TARGET_ACT_INFO);

		if (stubActInfo == null || targetActInfo == null) {
			return false;
		}

		boolean error = false;
		try {
			targetIntent.putExtra(ExtraConstants.EXTRA_STUB_ACT_INFO, stubActInfo);
			targetIntent.putExtra(ExtraConstants.EXTRA_TARGET_ACT_INFO, targetActInfo);
		} catch (Throwable e) {
			error = true;
			XLog.w(TAG, "Directly putExtra failed: %s.", e.getMessage());
		}
		if (error && Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
			// 4.4以下的设备会出现这个奇葩的问题(unParcel死活找不到类加载器),
			// 只能通过注入Class.forName所使用的类加载器来解决了...
			ClassLoader oldParent = ClassLoaderCompat.setParent(getClass().getClassLoader(), pluginClassLoader);
			try {
				targetIntent.putExtra(ExtraConstants.EXTRA_STUB_ACT_INFO, stubActInfo);
				targetIntent.putExtra(ExtraConstants.EXTRA_TARGET_ACT_INFO, targetActInfo);
			} catch (Throwable e) {
				XLog.w(TAG, "Secondly putExtra failed: %s.", e.getMessage());
			}
			ClassLoaderCompat.setParent(getClass().getClassLoader(), oldParent);
		}

		ActivityRecordCompat.setIntent(r, targetIntent);
		ActivityRecordCompat.setActivityInfo(r, targetActInfo);

		return true;
	}

	@Override
	public void inject() throws Throwable {
		otherCallback = getHCallback();
		f_handleCallback.set(getH(), this);
	}

	@Override
	public boolean isEnvBad() {
		Handler.Callback callback = getHCallback();
		boolean envBad = callback != this;
		if (envBad) {
			XLog.d(TAG, "HCallback has bad, other callback = " + callback);
		}
		return envBad;
	}

}
