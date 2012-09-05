package org.servalarch.serval;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.servalarch.servalctrl.HostCtrlCallbacks;
import org.servalarch.servalctrl.ServiceInfo;
import org.servalarch.servalctrl.ServiceInfoStat;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.text.Html;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;


public class ServalActivity extends FragmentActivity {

    private static final int DEFAULT_IDX = 2;

    private Button moduleStatusButton;
    private Button udpEncapButton;
	
    private SharedPreferences prefs;
    private PagerAdapter pagerAdapter;

    private ServalFragment servalFrag;
    private File filesDir;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
	{
	    super.onCreate(savedInstanceState);
	    filesDir = getExternalFilesDir(null);
	    
	    try {
		filesDir.createNewFile();
	    } catch (IOException e) {
		e.printStackTrace();
	    }

	    String[] modules = { "serval", "dummy" };
	    extractKernelModules(modules);

	    prefs = getSharedPreferences("serval", 0);
	    setContentView(R.layout.main);
	    List<Fragment> fragments = new Vector<Fragment>();
	    fragments.add(Fragment.instantiate(this, FlowTableFragment.class.getName()));
	    fragments.add(Fragment.instantiate(this, ServiceTableFragment.class.getName()));
	    servalFrag = (ServalFragment) Fragment.instantiate(this, ServalFragment.class.getName());
	    fragments.add(servalFrag);
	    fragments.add(Fragment.instantiate(this, TranslatorFragment.class.getName()));
	    this.pagerAdapter = new PagerAdapter(super.getSupportFragmentManager(), fragments);
	    ViewPager pager = (ViewPager) super.findViewById(R.id.pager);
	    pager.setAdapter(this.pagerAdapter);
	    pager.setCurrentItem(DEFAULT_IDX);
	    
	    this.moduleStatusButton = (Button) findViewById(R.id.moduleStatusToggle);
	    this.moduleStatusButton.setOnClickListener(new OnClickListener() {
			
		    @Override
		    public void onClick(View v) {
			boolean isLoaded = isModuleLoaded("serval");
			boolean addPersistent = !isLoaded;
			boolean result = false;

			Log.d("Serval", "Clicked moduleStatusButton");
			
			if (!moduleStatusButton.isSelected()) {
			    if (isLoaded) {
				setModuleLoaded(isLoaded);
				return;
			    }
			    result = loadKernelModule("serval");

			    if (!result) {
				Toast t = Toast.makeText(getApplicationContext(), 
							 "Failed to load the Serval kernel module.", 
							 Toast.LENGTH_SHORT);
				t.show();
			    }
			} else {					
			    AppHostCtrl.fini();					

			    if (!isLoaded) {
				setModuleLoaded(isLoaded);
				return;
			    }
			    result = unloadKernelModule("serval");
			}

			if (!isModuleLoaded("serval")) {
			    setModuleLoaded(false);
			    setUdpEncap(false);
			} else {
			    setModuleLoaded(true);
					
			    AppHostCtrl.init(cbs);
			    /* insert persistent rules */
			    if (addPersistent) {
				Map<String, ?> idMap = prefs.getAll();
				for (String srvID : idMap.keySet()) {
				    if (!(idMap.get(srvID) instanceof String))
					continue;
				    String addr = (String) idMap.get(srvID);
				    AppHostCtrl.performOp(getApplicationContext(), srvID, addr, AppHostCtrl.SERVICE_ADD);
				}
			    }
			}
		    }

		});
		
	    this.udpEncapButton = (Button) findViewById(R.id.udpEncapToggle);
	    this.udpEncapButton.setOnClickListener(new OnClickListener() {
		    @Override
		    public void onClick(View v) {
			String cmd;
				
			if (!isModuleLoaded("serval")) {
			    setUdpEncap(false);
			    return;
			}
				
			if (!udpEncapButton.isSelected())
			    cmd = "echo 1 > /proc/sys/net/serval/udp_encap";
			else
			    cmd = "echo 0 > /proc/sys/net/serval/udp_encap";

			if (!executeSuCommand(cmd)) {
			    Toast t = Toast.makeText(getApplicationContext(), cmd + " failed!", 
						     Toast.LENGTH_SHORT);
			    t.show();
			}
			setUdpEncap(isUdpEncapEnabled());
		    }
		});

	    Log.d("Serval", "onCreate finished");
	}
    
    void extractKernelModules(final String[] modules) {
	new Thread(new Runnable() {
		@Override
		public void run() {
		    for (String name : modules) {
			name += ".ko";
			final File module = new File(filesDir, name);
			
			Log.d("Serval", "extracting module " + name + " to " + module.getAbsolutePath());

			if (module.exists())
			    continue;
			
			try {
			    BufferedInputStream in = new BufferedInputStream(getAssets().open(name));
			    
			    byte[] buffer = new byte[1024];
			    int n, tot = 0;
			    
			    FileOutputStream os = new FileOutputStream(module);
			    BufferedOutputStream out = new BufferedOutputStream(os);
			    
			    while ((n = in.read(buffer, 0, 1024)) != -1) {
				out.write(buffer, 0, n);
				tot += n;
			    }
			    out.close();
			    in.close();
			    
			    Log.d("Serval", "Wrote " + tot + " bytes to " + module.getAbsolutePath());
			    
			} catch (IOException e) {
			    Log.d("Serval", "Could not extract " + name);
			    //e.printStackTrace();
			}
		    }
		}
	    }).start();
    }

    public boolean loadKernelModule(final String name) {
	return executeSuCommand( "insmod " + new File(filesDir, name + ".ko").getAbsolutePath());
    }

    public boolean unloadKernelModule(final String name) {
	return executeSuCommand( "rmmod " + new File(filesDir, name).getAbsolutePath());
    }
	
    boolean executeSuCommand(final String cmd) {
	Log.d("Serval", "executing su command: " + cmd);
	return executeSuCommand(cmd, false);
    }

    boolean executeSuCommand(final String cmd, boolean showToast) {
	try {
	    Process shell;
	    int err;

	    shell = Runtime.getRuntime().exec("su");
	    DataOutputStream os = new DataOutputStream(shell.getOutputStream());
	    os.writeBytes(cmd + "\n");
	    os.flush();
	    os.writeBytes("exit\n");
	    os.flush();
	    os.close();

	    err = shell.waitFor();

	    if (err == 0)
		return true;

	} catch (IOException e) {
	    e.printStackTrace();
	} catch (InterruptedException e) {
	    e.printStackTrace();
	}

	Log.d("Serval", cmd + " failed!");
	if (showToast)
	    Toast.makeText(getApplicationContext(), "'" + cmd +"' failed!", Toast.LENGTH_SHORT).show();

	return false;
    }
	
    private boolean isUdpEncapEnabled() {
	boolean encapIsEnabled = false;
	File encap = new File("/proc/sys/net/serval/udp_encap");

	if (encap.exists() && encap.canRead()) {
	    try {
		BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(encap)));

		String line = in.readLine();

		if (line.contains("1"))
		    encapIsEnabled = true;
	    } catch (FileNotFoundException e) {
		e.printStackTrace();
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	} else {
	    Log.d("Serval", "could not open /proc/sys/net/serval/udp_encap");
	}
	return encapIsEnabled;
    }
	
    private boolean isModuleLoaded(String module) {
	File procModules = new File("/proc/modules");

	if (procModules.exists() && procModules.canRead()) {
	    try {
		BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(procModules)));

		String line = null; 
		while ((line = in.readLine()) != null) {
		    if (line.contains(module)) {
			return true;
		    }
		}
	    } catch (FileNotFoundException e) {
		e.printStackTrace();
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	} else {
	    Log.d("Serval", "could not open /proc/modules");
	}
	return false;
    }
	
    public void setModuleLoaded(boolean loaded) {
	CharSequence text = Html.fromHtml(getString(loaded ?
						    R.string.module_loaded : R.string.module_unloaded));
	moduleStatusButton.setSelected(loaded);
	moduleStatusButton.setText(text);
    }
	
    public void setUdpEncap(boolean on) {
	CharSequence text = Html.fromHtml(getString(on ? R.string.udp_on : R.string.udp_off));
	udpEncapButton.setSelected(on);
	udpEncapButton.setText(text);
    }

    @Override
    public void onBackPressed() {
	super.onBackPressed();
    }

    private final HostCtrlCallbacks cbs = new HostCtrlCallbacks() {
	    @Override
	    public void onServiceAdd(long xid, final int retval, ServiceInfo[] info) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
			    String msg;
			    if (retval == RETVAL_OK) {
				msg = "Added service";
				if (servalFrag != null && servalFrag.servicePerm != null && servalFrag.servicePerm.isChecked()) {
				    Log.d("Serval", "Saving rule...");
				    prefs.edit().putString(servalFrag.editServiceText.getText().toString(), 
							   servalFrag.editIpText.getText().toString()).commit();
				}
			    }
			    else
				msg = "Add service failed retval=" + retval + " " + getRetvalString(retval);
					
			    Toast t = Toast.makeText(getApplicationContext(), msg, 
						     Toast.LENGTH_SHORT);
			    t.setGravity(Gravity.CENTER, 0, 0);
			    t.show();
			}
		    });
	    }

	    @Override
	    public void onServiceRemove(long xid, final int retval, ServiceInfoStat[] info) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
			    String msg;
			    if (retval == RETVAL_OK) { 
				msg = "Removed service";
				prefs.edit().remove(servalFrag.editServiceText.getText().toString()).commit();
			    }
			    else
				msg = "Remove service failed retval=" + retval + " " + getRetvalString(retval);
					
			    Toast t = Toast.makeText(getApplicationContext(), msg, 
						     Toast.LENGTH_LONG);
			    t.setGravity(Gravity.CENTER, 0, 0);
			    t.show();
			}
		    });
	    }

	    @Override
	    public void onServiceGet(long xid, final int retval, ServiceInfo[] info) {
		for (int i = 0; i < info.length; i++) {
		    Log.d("Serval", "RETRIEVED: Service " + info[i].getServiceID() + 
			  "address " + info[i].getAddress());
		}
	    }
	};
    
    
    
    @Override
    protected void onStart() {
	super.onStart();
	setModuleLoaded(isModuleLoaded("serval"));
	setUdpEncap(isUdpEncapEnabled());
	    
	AppHostCtrl.init(cbs);
	Log.d("Serval", "onStart finished");
    }
	
    @Override
    protected void onStop() {
	super.onStop();
	Log.d("Serval", "Stopping Serval host control");
    }
	
    @Override
    protected void onDestroy() {
	super.onDestroy();
	Log.d("Serval", "Destroying Serval host control");
	AppHostCtrl.fini();
    }
	
    private class PagerAdapter extends FragmentPagerAdapter {

	private List<Fragment> fragments;
	private String[] titles;

	public PagerAdapter(FragmentManager fm, List<Fragment> fragments) {
	    super(fm);
	    this.fragments = fragments;
	    this.titles = ServalActivity.this.getResources().getStringArray(R.array.pager_titles);
	}

	@Override
	public Fragment getItem(int position) {
	    return this.fragments.get(position);
	}
		
	@Override
	public CharSequence getPageTitle(int position) {
	    return titles[position];
	}
		
	@Override
	public int getCount() {
	    return this.fragments.size();
	}
    }
}