package de.debatingsocietypaderborn.debating_timer;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.SoundPool.OnLoadCompleteListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

public class DSPDebatingTimerActivity extends Activity {
	private SoundPool soundPool;
	private int soundID;
	boolean loaded = false;
	Timer timer;
	TimerTask timer_task;
	Handler handler;
	
	View main_view;
	EditText tvTime;
	TextView tvCurrentTime;
	Button btTime5min, btTime7min;
	Button btStartPause, btStop;
	
	private int protectedSecs = 60;
	private int debateTime = 60*7;
	
	private enum State { STOPPED, RUNNING, PAUSED }
	private State state;
	private long startMillis, pauseStartMillis;
	private long nextEvent;
	
	private final int STREAM = AudioManager.STREAM_NOTIFICATION;
	
	//private WakeLock brightLock, dimLock;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        //Context context = getApplicationContext();
        
        main_view = findViewById(R.id.main);
        tvTime = (EditText) findViewById(R.id.time);
        tvCurrentTime = (TextView) findViewById(R.id.tvCurrentTime);
        btTime5min = (Button) findViewById(R.id.time_5min);
        btTime7min = (Button) findViewById(R.id.time_7min);
        btStartPause = (Button) findViewById(R.id.btStartPause);
        btStop = (Button) findViewById(R.id.btStop);
        
        this.setVolumeControlStream(STREAM);
        
        /*PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        brightLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "bright");
        dimLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "dim");
        mWakeLock.acquire();*/
        
        // keep the screen on and bright
        this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
		// Load the sound
		soundPool = new SoundPool(10, STREAM, 0);
		soundPool.setOnLoadCompleteListener(new OnLoadCompleteListener() {
			@Override
			public void onLoadComplete(SoundPool soundPool, int sampleId,
					int status) {
				loaded = true;
			}
		});
		soundID = soundPool.load(this, R.raw.metal_hit, 1);
		
		state = State.STOPPED;
		timer = new Timer(true);
		handler = new Handler();
		
		tvTime.setOnFocusChangeListener(new OnFocusChangeListener() {
			@Override
			public void onFocusChange(View v, boolean hasFocus) {
				updateDebateTime();
			}
		});
		tvTime.setOnEditorActionListener(new OnEditorActionListener() {
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				updateDebateTime();
				return false;
			}
		});
        
		btTime5min.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				tvTime.setText("5.0");
			}
		});
		btTime7min.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				tvTime.setText("7.0");
			}
		});
		btStartPause.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				switch (state) {
				case STOPPED:
					changeState(State.RUNNING);
					break;
				case RUNNING:
					changeState(State.PAUSED);
					break;
				case PAUSED:
					changeState(State.RUNNING);
					break;
				}
			}
		});
		btStop.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				changeState(State.STOPPED);
			}
		});
		
		// hide keyboard
		//TODO this doesn't work...
		//InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		//imm.hideSoftInputFromWindow(tvTime.getWindowToken(), 0);
		//imm.hideSoftInputFromWindow(main_view.getWindowToken(), 0);
		//imm.hideSoftInputFromWindow(btStartPause.getWindowToken(), 0);
		//imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
		//imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
		//imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, InputMethodManager.HIDE_NOT_ALWAYS);
		
		if (savedInstanceState != null && !savedInstanceState.isEmpty()) {
			tvTime.setText(String.format(Locale.US, "%3.2f",
					savedInstanceState.getFloat("debateTime") / 60.0));
			updateDebateTime();
			changeState(State.valueOf(savedInstanceState.getString("state")));
			startMillis = savedInstanceState.getLong("startMillis");
			pauseStartMillis = savedInstanceState.getLong("pauseStartMillis");
			nextEvent = savedInstanceState.getLong("nextEvent");
			
			if (state != State.STOPPED) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						updateTimer();
					}
				});
			}
		}
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
    	super.onSaveInstanceState(outState);
    	
    	outState.putFloat("debateTime", debateTime);
    	outState.putString("state", state.toString());
    	outState.putLong("startMillis", startMillis);
    	outState.putLong("pauseStartMillis", pauseStartMillis);
    	outState.putLong("nextEvent", nextEvent);
    }
    
    @Override
    protected void onDestroy() {
    	super.onDestroy();
    	
    	if (timer_task != null)
    		timer_task.cancel();
    	if (timer != null)
    		timer.cancel();
    }
    
    @Override
    protected void onPause() {
    	super.onPause();
    	
    	if (timer_task != null)
    		timer_task.cancel();
    }
    
    @Override
    protected void onResume() {
    	super.onResume();
    	
    	if (timer_task != null && state == State.RUNNING) {
			timer_task = new TimerTask() {
				@Override
				public void run() {
					handler.post(new Runnable() {
						@Override
						public void run() {
							updateTimer();
						}
					});
				}
			};
			timer.scheduleAtFixedRate(timer_task, 0, 100);
    	}
    }
    
	protected void updateDebateTime() {
		if (state != State.STOPPED)
			return;
		
		String txt = tvTime.getText().toString();
		try {
			float mins = Float.parseFloat(txt);
			tvTime.setBackgroundColor(Color.WHITE);
			debateTime = Math.round(mins*60);
			if (debateTime <= 3*60)
				protectedSecs = 30;
			else
				protectedSecs = 60;
		} catch (NumberFormatException e) {
			tvTime.setBackgroundColor(Color.RED);
		}
	}

	void playSound() {
		// Getting the user sound settings
		AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
		float actualVolume = (float) audioManager
				.getStreamVolume(STREAM);
		float maxVolume = (float) audioManager
				.getStreamMaxVolume(STREAM);
		float volume = actualVolume / maxVolume;
		// Is the sound loaded already?
		if (loaded) {
			soundPool.play(soundID, volume, volume, 1, 0, 1f);
		}
	}
	
	void playSoundDelayed(int ms) {
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				playSound();
			}
		}, ms);
	}
	
	void changeState(State s) {
		if (s == state)
			return;
		
		switch (s) {
		case STOPPED:
			if (timer_task != null)
				timer_task.cancel();
			
			btStartPause.setText(R.string.start);
			btStop.setEnabled(false);

			btTime5min.setEnabled(true);
			btTime7min.setEnabled(true);
			tvTime.setEnabled(true);
			break;
			
		case RUNNING:
			if (state == State.STOPPED) {
				updateDebateTime();
				startMillis = System.currentTimeMillis();
				nextEvent = protectedSecs;
			} else if (state == State.PAUSED) {
				startMillis += System.currentTimeMillis() - pauseStartMillis;
			}
			
			timer_task = new TimerTask() {
				@Override
				public void run() {
					handler.post(new Runnable() {
						@Override
						public void run() {
							updateTimer();
						}
					});
				}
			};
			timer.scheduleAtFixedRate(timer_task, 0, 100);
			
			btStartPause.setText(R.string.pause);
			btStop.setEnabled(true);
			
			btTime5min.setEnabled(false);
			btTime7min.setEnabled(false);
			tvTime.setEnabled(false);
			break;
			
		case PAUSED:
			pauseStartMillis = System.currentTimeMillis();
			
			if (timer_task != null)
				timer_task.cancel();

			btStartPause.setText(R.string.resume);
			btStop.setEnabled(true);

			btTime5min.setEnabled(false);
			btTime7min.setEnabled(false);
			tvTime.setEnabled(false);
			break;
		}
		
		this.state = s;
		if (s == State.STOPPED)
			updateDebateTime();
	}
	
	private void updateTimer() {
		long millis;
		if (state != State.PAUSED)
			millis = System.currentTimeMillis();
		else
			millis = pauseStartMillis;
		millis -= startMillis;
		long secs = millis / 1000;

		tvCurrentTime.setText(String.format("%d:%02d", secs / 60, secs % 60));
		//tvCurrentTime.setText(String.format("%d:%02d, %d", secs / 60, secs % 60, nextEvent-secs));
		
		//TODO audible signal should be optional
		//TODO debate status should be visible (protected time, normal time, overtime)
		if (secs >= nextEvent) {
			int count = 0;
			if (nextEvent == protectedSecs) {
				count = 1;
				nextEvent = debateTime - protectedSecs;
			} else if (nextEvent < debateTime) {
				count = 1;
				nextEvent = debateTime;
			} else if (nextEvent == debateTime) {
				count = 2;
				nextEvent += 15;
			} else {
				count = 3;
				nextEvent += 15;
			}
			
			if (count > 0) {
				// flash screen in addition to audible signal
				main_view.setBackgroundColor(Color.WHITE);
				handler.postDelayed(new Runnable() {
					@Override
					public void run() {
						main_view.setBackgroundColor(Color.parseColor("#158a9c"));
					}
				}, 100);
				
				// audible signal
				playSound();
				for (int i=1;i<count;i++) {
					playSoundDelayed(200 * i);
				}
			}
		}
	}
}
