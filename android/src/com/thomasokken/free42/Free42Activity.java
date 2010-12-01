/*****************************************************************************
 * Free42 -- an HP-42S calculator simulator
 * Copyright (C) 2004-2010  Thomas Okken
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License, version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see http://www.gnu.org/licenses/.
 *****************************************************************************/

package com.thomasokken.free42;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Paint.Style;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.ClipboardManager;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * This Activity class contains most of the Free42 'shell' functionality;
 * the skin-specific code is separated into the SkinLayout class.
 * This class works in conjunction with free42glue.cc, which is the JNI-
 * based interface to the Free42 'core' functionality (the core is
 * C++ and porting it to Java is not practical, hence the use of JNI).
 */
public class Free42Activity extends Activity {
    
	private static final int MENU_ID_COPY = Menu.FIRST;
	private static final int MENU_ID_PASTE = Menu.FIRST + 1;
	private static final int MENU_ID_FLIP_CALC_PRINTOUT = Menu.FIRST + 2;
	private static final int MENU_ID_IMPORT = Menu.FIRST + 3;
	private static final int MENU_ID_EXPORT = Menu.FIRST + 4;
	private static final int MENU_ID_PREFERENCES = Menu.FIRST + 5;

    private static final int SHELL_VERSION = 1;
    
    static {
    	System.loadLibrary("free42");
    }

    private CalcView calcView;
    private SkinLayout skin;
    private PrintView printView;
    private ScrollView printScrollView;
    private boolean printViewShowing;
    private PreferencesDialog preferencesDialog;
    
    // Streams for reading and writing the state file
    private InputStream stateFileInputStream;
    private OutputStream stateFileOutputStream;
    
    // Streams for program import and export
    private InputStream programsInputStream;
    private OutputStream programsOutputStream;
    
    // Stuff to run core_keydown() on a background thread
    private CoreThread coreThread;
	private boolean coreWantsCpu;
	
	private int ckey;
	private Timer key_timer;
	private Timer timer3;

	// Persistent state
	private boolean printToTxt;
	private String printToTxtFileName = "";
	private boolean printToGif;
	private String printToGifFileName = "";
	private int maxGifHeight = 256;
	
    
    ///////////////////////////////////////////////////////
    ///// Top-level code to interface with Android UI /////
    ///////////////////////////////////////////////////////
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        skin = new SkinLayout("Ehrling42sm");
        calcView = new CalcView(this);
        setContentView(calcView);
        printView = new PrintView(this);
        printScrollView = new ScrollView(this);
        printScrollView.addView(printView);
        
    	int init_mode;
		IntHolder version = new IntHolder();
    	try {
    		stateFileInputStream = openFileInput("state");
    	} catch (FileNotFoundException e) {
    		stateFileInputStream = null;
    	}
    	if (stateFileInputStream != null) {
            if (read_shell_state(version))
                init_mode = 1;
            else {
                init_shell_state(-1);
                init_mode = 2;
            }
        } else {
            init_shell_state(-1);
            init_mode = 0;
        }
    	
    	nativeInit();
    	core_init(init_mode, version.value);
    	if (stateFileInputStream != null) {
    		try {
    			stateFileInputStream.close();
    		} catch (IOException e) {}
    		stateFileInputStream = null;
    	}
    	
    	if (core_powercycle())
    		/* TODO: Start program running */;
    }

    @Override
    protected void onDestroy() {
    	new Exception().printStackTrace();
    	end_core_keydown();
    	super.onDestroy();
    	// Write state file
    	try {
    		stateFileOutputStream = openFileOutput("state", Context.MODE_PRIVATE);
    	} catch (FileNotFoundException e) {
    		stateFileOutputStream = null;
    	}
    	if (stateFileOutputStream != null) {
    		write_shell_state();
    		core_quit();
    	}
    	if (stateFileOutputStream != null) {
    		try {
    			stateFileOutputStream.close();
    		} catch (IOException e) {}
    		stateFileOutputStream = null;
    	}
    	printView.dump();
    }
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (printViewShowing && keyCode == KeyEvent.KEYCODE_BACK) {
			doFlipCalcPrintout();
			return true;
		} else {
			return super.onKeyDown(keyCode, event);
		}
	}
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        menu.add(0, MENU_ID_COPY, 0, "Copy");
        menu.add(0, MENU_ID_PASTE, 0, "Paste");
        menu.add(0, MENU_ID_FLIP_CALC_PRINTOUT, 0, "Print-Out");
        menu.add(0, MENU_ID_IMPORT, 0, "Import");
        menu.add(0, MENU_ID_EXPORT, 0, "Export");
        menu.add(0, MENU_ID_PREFERENCES, 0, "Preferences");
        menu.getItem(0).setIcon(R.drawable.copy);
        menu.getItem(1).setIcon(R.drawable.paste);
        menu.getItem(2).setIcon(R.drawable.printer);
        menu.getItem(3).setIcon(android.R.drawable.ic_menu_upload);
        menu.getItem(4).setIcon(android.R.drawable.ic_menu_save);
        menu.getItem(5).setIcon(android.R.drawable.ic_menu_preferences);
        
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case MENU_ID_COPY:
            doCopy();
            return true;
        case MENU_ID_PASTE:
        	doPaste();
            return true;
        case MENU_ID_FLIP_CALC_PRINTOUT:
        	doFlipCalcPrintout();
            return true;
        case MENU_ID_IMPORT:
        	doImport();
            return true;
        case MENU_ID_EXPORT:
        	doExport();
            return true;
        case MENU_ID_PREFERENCES:
        	doPreferences();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void doCopy() {
    	ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    	clip.setText(core_copy());
    }
    
    private void doPaste() {
    	ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    	if (clip.hasText()) {
    		core_paste(clip.getText().toString());
    		redisplay();
    	}
    }
    
    private void doFlipCalcPrintout() {
    	printViewShowing = !printViewShowing;
    	setContentView(printViewShowing ? printScrollView : calcView);
    }
    
    private void doImport() {
    	// TODO
    }
    
    private void doExport() {
    	// TODO
    }
    
    private void doPreferences() {
    	if (preferencesDialog == null) {
    		preferencesDialog = new PreferencesDialog(this);
    		preferencesDialog.setOkListener(new PreferencesDialog.OkListener() {
    			public void okPressed() {
    				doPreferencesDismissed();
    			}
    		});
    	}
    	
    	CoreSettings cs = new CoreSettings();
    	getCoreSettings(cs);
    	preferencesDialog.setSingularMatrixError(cs.matrix_singularmatrix);
    	preferencesDialog.setMatrixOutOfRange(cs.matrix_outofrange);
    	preferencesDialog.setAutoRepeat(cs.auto_repeat);
    	preferencesDialog.setPrintToText(printToTxt);
    	preferencesDialog.setPrintToTextFileName(printToTxtFileName);
    	preferencesDialog.setRawText(cs.raw_text);
    	preferencesDialog.setPrintToGif(printToGif);
    	preferencesDialog.setPrintToGifFileName(printToGifFileName);
    	preferencesDialog.setMaxGifHeight(maxGifHeight);
    	preferencesDialog.show();
    }
    	
    private void doPreferencesDismissed() {
    	CoreSettings cs = new CoreSettings();
    	getCoreSettings(cs);
    	cs.matrix_singularmatrix = preferencesDialog.getSingularMatrixError();
    	cs.matrix_outofrange = preferencesDialog.getMatrixOutOfRange();
    	cs.auto_repeat = preferencesDialog.getAutoRepeat();
    	printToTxt = preferencesDialog.getPrintToText();
    	printToTxtFileName = preferencesDialog.getPrintToTextFileName();
    	cs.raw_text = preferencesDialog.getRawText();
    	printToGif = preferencesDialog.getPrintToGif();
    	printToGifFileName = preferencesDialog.getPrintToGifFileName();
    	maxGifHeight = preferencesDialog.getMaxGifHeight();
    	putCoreSettings(cs);
    }
    
    /**
     * This class is calculator view used by the Free42 Activity.
     * Note that most of the heavy lifting takes place in the
     * Activity, not here.
     */
    private class CalcView extends View {

    	public CalcView(Context context) {
    		super(context);
    	}
    	
    	@Override
    	protected void onDraw(Canvas canvas) {
    		skin.repaint(canvas);
    	}
    	
    	@Override
    	public boolean onTouchEvent(MotionEvent e) {
    		int what = e.getAction();
    		if (what != MotionEvent.ACTION_DOWN && what != MotionEvent.ACTION_UP)
    			return true;
    		
    		if (what == MotionEvent.ACTION_DOWN) {
	    		int x = (int) e.getX();
	    		int y = (int) e.getY();
	    		IntHolder skeyHolder = new IntHolder();
	    		IntHolder ckeyHolder = new IntHolder();
	    		skin.find_key(core_menu(), x, y, skeyHolder, ckeyHolder);
	    		int skey = skeyHolder.value;
	    		ckey = ckeyHolder.value;
	    		if (ckey == 0)
	    			return true;
	    		click();
	    		end_core_keydown();
	        	byte[] macro = skin.find_macro(ckey);
	            if (timer3 != null && (macro != null || ckey != 28 /* SHIFT */)) {
		        	timer3.cancel();
	                timer3 = null;
	                core_timeout3(0);
	            }
		        Rect inval = skin.set_active_key(skey);
		        if (inval != null)
		        	invalidate(inval);
		        boolean running;
				BooleanHolder enqueued = new BooleanHolder();
				IntHolder repeat = new IntHolder();
		        if (macro == null) {
		        	// Plain ol' key
					running = core_keydown(ckey, enqueued, repeat, true);
		        } else {
					boolean one_key_macro = macro.length == 1 || (macro.length == 2 && macro[0] == 28);
					if (!one_key_macro)
						skin.set_display_enabled(false);
					for (int i = 0; i < macro.length - 1; i++) {
						core_keydown(macro[i] & 255, enqueued, repeat, true);
						if (!enqueued.value)
							core_keyup();
					}
					running = core_keydown(macro[macro.length - 1] & 255, enqueued, repeat, true);
					if (!one_key_macro)
						skin.set_display_enabled(true);
		        }
		        if (running)
		        	start_core_keydown();
		        else {
		    		if (key_timer != null)
		    			key_timer.cancel();
		        	if (repeat.value != 0) {
		        		key_timer = new Timer();
		        		TimerTask task = new TimerTask() {
		        			public void run() {
		        				repeater();
		        			}
		        		};
		        		Date when = new Date(new Date().getTime() + (repeat.value == 1 ? 1000 : 500));
		        		key_timer.schedule(task, when);
		        	} else if (!enqueued.value) {
		        		key_timer = new Timer();
		        		TimerTask task = new TimerTask() {
		        			public void run() {
		        				timeout1();
		        			}
		        		};
		        		Date when = new Date(new Date().getTime() + 250);
		        		key_timer.schedule(task, when);       		
		        	} else
		        		key_timer = null;
		        }
    	    } else {
    	    	ckey = 0;
    	    	Rect inval = skin.set_active_key(-1);
    	    	if (inval != null)
    	    		invalidate(inval);
	    		end_core_keydown();
    			coreWantsCpu = core_keyup();
    			if (coreWantsCpu)
    		        start_core_keydown();
    	    }
    			
    		return true;
    	}
    }

    /**
     * This class is the print-out view used by the Free42 Activity.
     * Note that most of the heavy lifting takes place in the
     * Activity, not here.
     */
    private class PrintView extends View {
    	
    	private static final int BYTESPERLINE = 18;
    	private static final int LINES = 16384;
    	
    	private byte[] buffer = new byte[LINES * BYTESPERLINE];
    	private int top, bottom;
    	private int printHeight;

    	public PrintView(Context context) {
    		super(context);
    		InputStream printInputStream = null;
    		try {
    			printInputStream = openFileInput("print");
    			byte[] intBuf = new byte[4];
    			if (printInputStream.read(intBuf) != 4)
    				throw new IOException();
    			int len = (intBuf[0] << 24) | ((intBuf[1] & 255) << 16) | ((intBuf[2] & 255) << 8) | (intBuf[3] & 255);
    			int n = printInputStream.read(buffer, 0, len);
    			if (n != len)
    				throw new IOException();
    			top = 0;
    			bottom = len;
    		} catch (IOException e) {
    			top = bottom = 0;
    		} finally {
    			if (printInputStream != null)
    				try {
    					printInputStream.close();
    				} catch (IOException e2) {}
    		}

    		printHeight = bottom / BYTESPERLINE;
    	}

    	@Override
    	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    		setMeasuredDimension(286, printHeight * 2);
    	}

    	@Override
    	protected void onDraw(Canvas canvas) {
    		Paint p = new Paint();
    		p.setColor(Color.RED);
    		p.setStyle(Style.FILL);
    		canvas.drawRect(canvas.getClipBounds(), p);
    		//canvas.drawText("Hello, world!", 20, 20, p);
    	}
    	
    	@Override
    	public boolean onTouchEvent(MotionEvent e) {
    		return true;
    	}
    	
    	public int getPrintHeight() {
    		return printHeight;
    	}
    	
    	public void print(byte[] bits, int bytesperline, int x, int y, int width, int height) {
			int oldPrintHeight = printHeight;
    		for (int yy = y; yy < y + height; yy++) {
    			for (int xx = 0; xx < BYTESPERLINE; xx++)
    				buffer[bottom + xx] = 0;
    			for (int xx = x; xx < x + width; xx++) {
    				boolean set = (bits[xx >> 3] & (1 << (xx & 7))) != 0;
    				if (set)
    					buffer[bottom + (xx >> 3)] |= 1 << (xx & 7);
    			}
    			bottom += BYTESPERLINE;
    			printHeight++;
    			if (bottom >= buffer.length)
    				bottom = 0;
    			if (bottom == top) {
    				top += BYTESPERLINE;
    				printHeight--;
    				if (top >= buffer.length)
    					top = 0;
    			}
    		}
			if (printHeight != oldPrintHeight)
				printScrollView.requestLayout();
    	}
    	
    	public void clear() {
    		top = bottom = 0;
    		printHeight = 0;
			printScrollView.requestLayout();
    	}
    	
    	public void dump() {
    		OutputStream printOutputStream = null;
    		try {
    			printOutputStream = openFileOutput("print", Context.MODE_PRIVATE);
    			int len = bottom - top;
    			if (len < 0)
    				len += buffer.length;
    			byte[] intBuf = new byte[4];
    			intBuf[0] = (byte) (len >> 24);
    			intBuf[1] = (byte) (len >> 16);
    			intBuf[2] = (byte) (len >> 8);
    			intBuf[3] = (byte) len;
    			printOutputStream.write(intBuf);
    			if (top <= bottom)
    				printOutputStream.write(buffer, top, bottom - top);
    			else {
    				printOutputStream.write(buffer, top, buffer.length - top);
    				printOutputStream.write(buffer, 0, bottom);
    			}
    		} catch (IOException e) {
    			// Ignore
    		} finally {
    			if (printOutputStream != null)
    				try {
    					printOutputStream.close();
    				} catch (IOException e2) {}
    		}
    	}
    }

    
    ////////////////////////////////////////////////////////////////////
    ///// This section is where all the real 'shell' work is done. /////
    ////////////////////////////////////////////////////////////////////
    
    private boolean read_shell_state(IntHolder version) {
    	try {
    		if (state_read_int() != FREE42_MAGIC())
    			return false;
    		version.value = state_read_int();
    		if (version.value < 0 || version.value > FREE42_VERSION())
    			return false;
    		int shell_version = state_read_int();
    		printToGif = state_read_boolean();
    	    printToGifFileName = state_read_string();
    	    printToTxt = state_read_boolean();
    	    printToTxtFileName = state_read_string();
    	    if (shell_version >= 1)
    	    	maxGifHeight = state_read_int();
    		init_shell_state(shell_version);
    	} catch (IllegalArgumentException e) {
    		return false;
    	}
    	return true;
    }
    
    private void init_shell_state(int shell_version) {
    	switch (shell_version) {
    	case -1:
    	    printToGif = false;
    	    printToGifFileName = "";
    	    printToTxt = false;
    	    printToTxtFileName = "";
    	    // fall through
    	case 0:
    		maxGifHeight = 256;
    	    // fall through
    	case 1:
			// current version (SHELL_VERSION = 0),
			// so nothing to do here since everything
			// was initialized from the state file.
    		;
    	}
    }

    private void write_shell_state() {
    	try {
    		state_write_int(FREE42_MAGIC());
    		state_write_int(FREE42_VERSION());
    		state_write_int(SHELL_VERSION);
    		state_write_boolean(printToGif);
    		state_write_string(printToGifFileName);
    		state_write_boolean(printToTxt);
    		state_write_string(printToTxtFileName);
    		state_write_int(maxGifHeight);
    	} catch (IllegalArgumentException e) {}
    }
    
	private byte[] int_buf = new byte[4];
    private int state_read_int() throws IllegalArgumentException {
    	if (shell_read_saved_state(int_buf) != 4)
    		throw new IllegalArgumentException();
    	return (int_buf[0] << 24) | ((int_buf[1] & 255) << 16) | ((int_buf[2] & 255) << 8) | (int_buf[3] & 255);
    }
    private void state_write_int(int i) throws IllegalArgumentException {
    	int_buf[0] = (byte) (i >> 24);
    	int_buf[1] = (byte) (i >> 16);
    	int_buf[2] = (byte) (i >> 8);
    	int_buf[3] = (byte) i;
    	if (!shell_write_saved_state(int_buf))
    		throw new IllegalArgumentException();
    }
    
	private byte[] boolean_buf = new byte[1];
    private boolean state_read_boolean() throws IllegalArgumentException {
    	if (shell_read_saved_state(boolean_buf) != 1)
    		throw new IllegalArgumentException();
    	return boolean_buf[0] != 0;
    }
    private void state_write_boolean(boolean b) throws IllegalArgumentException {
    	boolean_buf[0] = (byte) (b ? 1 : 0);
    	if (!shell_write_saved_state(boolean_buf))
    		throw new IllegalArgumentException();
    }
    
    private String state_read_string() throws IllegalArgumentException {
    	int length = state_read_int();
    	byte[] buf = new byte[length];
    	if (length > 0 && shell_read_saved_state(buf) != length)
    		throw new IllegalArgumentException();
    	try {
    		return new String(buf, "UTF-8");
    	} catch (UnsupportedEncodingException e) {
    		// Won't happen; UTF-8 is always supported.
    		return null;
    	}
    }
    private void state_write_string(String s) throws IllegalArgumentException {
    	byte[] buf;
    	try {
    		buf = s.getBytes("UTF-8");
    	} catch (UnsupportedEncodingException e) {
    		// Won't happen; UTF-8 is always supported.
    		throw new IllegalArgumentException();
    	}
    	state_write_int(buf.length);
    	shell_write_saved_state(buf);
    }

    private class CoreThread extends Thread {
    	public boolean enqueued;
    	public int repeat;
    	public boolean coreWantsCpu;
    	public void run() {
    		BooleanHolder enqHolder = new BooleanHolder();
    		IntHolder repHolder = new IntHolder();
    		coreWantsCpu = core_keydown(0, enqHolder, repHolder, false);
    		enqueued = enqHolder.value;
    		repeat = repHolder.value;
    	}
    }
    
    private void start_core_keydown() {
    	coreThread = new CoreThread();
    	coreThread.start();
    }
    
    private void end_core_keydown() {
		if (coreThread != null) {
			core_keydown_finish();
			try {
				coreThread.join();
			} catch (InterruptedException e) {}
			coreWantsCpu = coreThread.coreWantsCpu;
			coreThread = null;
		} else {
			coreWantsCpu = false;
		}
    }
    
	private void repeater() {
		if (key_timer != null)
			key_timer.cancel();
		if (ckey == 0) {
			key_timer = null;
			return;
		}
		key_timer = new Timer();
		int repeat = core_repeat();
		if (repeat != 0) {
			TimerTask task = new TimerTask() {
				public void run() {
					repeater();
				}
			};
			Date when = new Date(new Date().getTime() + (repeat == 1 ? 200 : 100));
			key_timer.schedule(task, when);
		} else {
			TimerTask task = new TimerTask() {
				public void run() {
					timeout1();
				}
			};
			Date when = new Date(new Date().getTime() + 250);
			key_timer.schedule(task, when);
		}
	}

	private void timeout1() {
		if (key_timer != null)
			key_timer.cancel();
		if (ckey != 0) {
			key_timer = new Timer();
			core_keytimeout1();
			TimerTask task = new TimerTask() {
				public void run() {
					timeout2();
				}
			};
			Date when = new Date(new Date().getTime() + 1750);
			key_timer.schedule(task, when);
		} else
			key_timer = null;
	}

	private void timeout2() {
		if (key_timer != null)
			key_timer.cancel();
		key_timer = null;
		if (ckey != 0)
			core_keytimeout2();
	}

	private void timeout3() {
		if (timer3 != null)
			timer3.cancel();
		timer3 = null;
		end_core_keydown();
		core_timeout3(1);
		// Resume program after PSE
		BooleanHolder enqueued = new BooleanHolder();
		IntHolder repeat = new IntHolder();
		boolean running = core_keydown(0, enqueued, repeat, true);
		if (running)
			start_core_keydown();
	}
	
	private void click() {
		MediaPlayer mp = MediaPlayer.create(this, R.raw.click);
		mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			public void onCompletion(MediaPlayer player) {
				player.release();
			}
		});
		mp.start();
	}
	

    //////////////////////////////////////////////////////////////////////////
    ///// Stubs for accessing the FREE42_MAGIC and FREE42_VERSION macros /////
    //////////////////////////////////////////////////////////////////////////
    
    private native int FREE42_MAGIC();
    private native int FREE42_VERSION();
    
    ///////////////////////////////////////////
    ///// Stubs for shell->core interface /////
    ///////////////////////////////////////////
    
    private native void nativeInit();
    private native void core_keydown_finish();
    
    private native void core_init(int read_state, int version);
    private native void core_quit();
    private native void core_repaint_display();
    private native boolean core_menu();
    private native boolean core_alpha_menu();
    private native boolean core_hex_menu();
    private native boolean core_keydown(int key, BooleanHolder enqueued, IntHolder repeat, boolean immediate_return);
    private native int core_repeat();
    private native void core_keytimeout1();
    private native void core_keytimeout2();
    private native boolean core_timeout3(int repaint);
    private native boolean core_keyup();
    private native boolean core_powercycle();
    private native int core_list_programs(byte[] buf);
    private native int core_program_size(int prgm_index);
    private native boolean core_export_programs(int[] indexes);
    private native void core_import_programs();
    private native String core_copy();
    private native void core_paste(String s);
    private native void getCoreSettings(CoreSettings settings);
    private native void putCoreSettings(CoreSettings settings);
    private native void redisplay();

    private static class CoreSettings {
		public boolean matrix_singularmatrix;
		public boolean matrix_outofrange;
		public boolean raw_text;
		public boolean auto_repeat;
		public boolean enable_ext_copan;
		public boolean enable_ext_bigstack;
		public boolean enable_ext_accel;
		public boolean enable_ext_locat;
		public boolean enable_ext_heading;
		public boolean enable_ext_time;
    }

    ///////////////////////////////////////////////////
    ///// Implementation of core->shell interface /////
    ///////////////////////////////////////////////////
    
	/**
	 * shell_blitter()
	 *
	 * Callback invoked by the emulator core to cause the display, or some portion
	 * of it, to be repainted.
	 *
	 * 'bits' is a pointer to a 1 bpp (monochrome) bitmap. The bits within a byte
	 * are laid out with left corresponding to least significant, right
	 * corresponding to most significant; this corresponds to the convention for
	 * X11 images, but it is the reverse of the convention for MacOS and its
	 * derivatives (Microsoft Windows and PalmOS).
	 * The bytes are laid out sequentially, that is, bits[0] is at the top
	 * left corner, bits[1] is to the right of bits[0], bits[2] is to the right of
	 * bits[1], and so on; this corresponds to X11, MacOS, Windows, and PalmOS
	 * usage.
	 * 'bytesperline' is the number of bytes per line of the bitmap; this means
	 * that the bits just below bits[0] are at bits[bytesperline].
	 * 'x', 'y', 'width', and 'height' define the part of the bitmap that needs to
	 * be repainted. 'x' and 'y' are 0-based coordinates, with (0, 0) being the top
	 * left corner of the bitmap, and x coordinates increasing to the right, and y
	 * coordinates increasing downwards. 'width' and 'height' are the width and
	 * height of the area to be repainted.
	 */
    public void shell_blitter(byte[] bits, int bytesperline, int x, int y, int width, int height) {
    	Rect inval = skin.display_blitter(bits, bytesperline, x, y, width, height);
    	calcView.postInvalidate(inval.left, inval.top, inval.right, inval.bottom);
    }

	/**
	 * shell_beeper()
	 * Callback invoked by the emulator core to play a sound.
	 * The first parameter is the frequency in Hz; the second is the
	 * duration in ms. The sound volume is up to the GUI to control.
	 * Sound playback should be synchronous (the beeper function should
	 * not return until the sound has finished), if possible.
	 */
	public void shell_beeper(int frequency, int duration) {
		int sound_number = 10;
		for (int i = 0; i < 10; i++) {
			if (frequency <= cutoff_freqs[i]) {
				sound_number = i;
				break;
			}
		}
		MediaPlayer mp = MediaPlayer.create(this, sound_ids[sound_number]);
		mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			public void onCompletion(MediaPlayer player) {
				player.release();
			}
		});
		mp.start();
		try {
			Thread.sleep(sound_number == 10 ? 125 : 250);
		} catch (InterruptedException e) {}
	}

	private final int[] cutoff_freqs = { 164, 220, 243, 275, 293, 324, 366, 418, 438, 550 };
	private final int[] sound_ids = { R.raw.tone0, R.raw.tone1, R.raw.tone2, R.raw.tone3, R.raw.tone4, R.raw.tone5, R.raw.tone6, R.raw.tone7, R.raw.tone8, R.raw.tone9, R.raw.squeak };
	
	/**
	 * shell_annunciators()
	 * Callback invoked by the emulator core to change the state of the display
	 * annunciators (up/down, shift, print, run, battery, (g)rad).
	 * Every parameter can have values 0 (turn off), 1 (turn on), or -1 (leave
	 * unchanged).
	 * The battery annunciator is missing from the list; this is the only one of
	 * the lot that the emulator core does not actually have any control over, and
	 * so the shell is expected to handle that one by itself.
	 */
	public void shell_annunciators(int updn, int shf, int prt, int run, int g, int rad) {
    	Rect inval = skin.update_annunciators(updn, shf, prt, run, g, rad);
    	if (inval != null)
    		calcView.postInvalidate(inval.left, inval.top, inval.right, inval.bottom);
	}
	
	/**
	 * Callback to ask the shell to call core_timeout3() after the given number of
	 * milliseconds. If there are keystroke events during that time, the timeout is
	 * cancelled. (Pressing 'shift' does not cancel the timeout.)
	 * This function supports the delay after SHOW, MEM, and shift-VARMENU.
	 */
	public void shell_request_timeout3(int delay) {
		if (timer3 != null)
			timer3.cancel();
		timer3 = new Timer();
		TimerTask task = new TimerTask() {
			public void run() {
				timeout3();
			}
		};
		Date when = new Date(new Date().getTime() + delay);
		timer3.schedule(task, when);
	}
	
	/**
	 * shell_read_saved_state()
	 *
	 * Callback to read from the saved state. The function will read up to n
	 * bytes into the buffer pointed to by buf, and return the number of bytes
	 * actually read. The function returns -1 if an error was encountered; a return
	 * value of 0 signifies the end of input.
	 * The emulator core should only call this function from core_init(), and only
	 * if core_init() was called with an argument of 1. (Nothing horrible will
	 * happen if you try to call this function during other contexts, but you will
	 * always get an error then.)
	 */
	public int shell_read_saved_state(byte[] buf) {
		if (stateFileInputStream == null)
			return -1;
		try {
			int n = stateFileInputStream.read(buf);
			if (n <= 0) {
				stateFileInputStream.close();
				stateFileInputStream = null;
				return 0;
			} else
				return n;
		} catch (IOException e) {
			try {
				stateFileInputStream.close();
			} catch (IOException e2) {}
			stateFileInputStream = null;
			return -1;
		}
	}
	
	/**
	 * shell_write_saved_state()
	 * Callback to dump the saved state to persistent storage.
	 * Returns 'true' on success, 'false' on error.
	 * The emulator core should only call this function from core_quit(). (Nothing
	 * horrible will happen if you try to call this function during other contexts,
	 * but you will always get an error then.)
	 */
	public boolean shell_write_saved_state(byte[] buf) {
		if (stateFileOutputStream == null)
			return false;
		try {
			stateFileOutputStream.write(buf);
			return true;
		} catch (IOException e) {
			try {
				stateFileOutputStream.close();
			} catch (IOException e2) {}
			stateFileOutputStream = null;
			return false;
		}
	}
	
	/**
	 * shell_get_mem()
	 * Callback to get the amount of free memory in bytes.
	 */
	public int shell_get_mem() {
		long freeMem = Runtime.getRuntime().freeMemory();
		return freeMem > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) freeMem;
	}
	
	/**
	 * shell_low_battery()
	 * Callback to find out if the battery is low. Used to emulate flag 49 and the
	 * battery annunciator, and also taken into account when deciding whether or
	 * not to allow a power-down -- so as long as the shell provides a functional
	 * implementation of shell_low_battery(), it can leave the decision on how to
	 * respond to sysNotifySleepRequestEvent to core_allows_powerdown().
	 */
	public int shell_low_battery() {
		// TODO -- see android.os.BatteryManager
		return 0;
	}
	
	/**
	 * shell_powerdown()
	 * Callback to tell the shell that the emulator wants to power down.
	 * Only called in response to OFF (shift-EXIT or the OFF command); automatic
	 * power-off is left to the OS and/or shell.
	 */
	public void shell_powerdown() {
        finish();
	}
	
	/**
	 * shell_random_seed()
	 * When SEED is invoked with X = 0, the random number generator should be
	 * seeded to a random value; the emulator core calls this function to obtain
	 * it. The shell should construct a double in the range [0, 1) in a random
	 * manner, using the real-time clock or some other source of randomness.
	 * Note that distribution is not very important; the value will only be used to
	 * seed the RNG. What's important that using shell_random_seed() guarantees
	 * that the RNG will be initialized to a different sequence. This matters for
	 * applications like games where you don't want the same sequence of cards
	 * dealt each time.
	 */
	public double shell_random_seed() {
		long t = new Date().getTime();
		return (t % 1000000000) / 1000000000d;
	}
	
	/**
	 * shell_print()
	 * Printer emulation. The first 2 parameters are the plain text version of the
	 * data to be printed; the remaining 6 parameters are the bitmap version. The
	 * former is used for text-mode copying and for spooling to text files; the
	 * latter is used for graphics-mode coopying, spooling to image files, and
	 * on-screen display.
	 */
	public void shell_print(byte[] text, byte[] bits, int bytesperline,
							int x, int y, int width, int height) {
		// TODO: printing to files
		printView.print(bits, bytesperline, x, y, width, height);
	}
	
	/**
	 * shell_write()
	 *
	 * Callback for core_export_programs(). Returns 0 if a problem occurred;
	 * core_export_programs() should abort in that case.
	 */
	public int shell_write(byte[] buf) {
		if (programsOutputStream == null)
			return 0;
		try {
			programsOutputStream.write(buf);
			return 1;
		} catch (IOException e) {
			try {
				programsOutputStream.close();
			} catch (IOException e2) {}
			programsOutputStream = null;
			return 0;
		}
	}
	
	/**
	 * shell_read()
	 *
	 * Callback for core_import_programs(). Returns the number of bytes actually
	 * read. Returns -1 if an error occurred; a return value of 0 signifies end of
	 * input.
	 */
	public int shell_read(byte[] buf) {
		if (programsInputStream == null)
			return -1;
		try {
			int n = programsInputStream.read(buf);
			if (n <= 0) {
				programsInputStream.close();
				programsInputStream = null;
				return 0;
			} else
				return n;
		} catch (IOException e) {
			try {
				programsInputStream.close();
			} catch (IOException e2) {}
			programsInputStream = null;
			return -1;
		}
	}
	
	public void shell_log(String s) {
		System.err.println(s);
	}
}