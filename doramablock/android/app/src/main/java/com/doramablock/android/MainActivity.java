package com.doramablock.android;
import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
public final class MainActivity extends Activity {
 private static final int R=710;
 public void onCreate(Bundle b){
  super.onCreate(b);
  LinearLayout l=new LinearLayout(this); l.setOrientation(1);
  Button a=new Button(this); a.setText("ATIVAR FILTRO DNS");
  Button s=new Button(this); s.setText("PARAR FILTRO");
  l.addView(a); l.addView(s); setContentView(l);
  a.setOnClickListener(v->{Intent p=VpnService.prepare(this);
   if(p!=null) startActivityForResult(p,R); else startVpn();});
  s.setOnClickListener(v->{Intent i=new Intent(this,AdsVpnService.class);
   i.setAction(AdsVpnService.ACTION_STOP); startService(i);});
 }
 protected void onActivityResult(int r,int c,Intent d){
  super.onActivityResult(r,c,d); if(r==R&&c==RESULT_OK) startVpn();
 }
 private void startVpn(){Intent i=new Intent(this,AdsVpnService.class);
  i.setAction(AdsVpnService.ACTION_START);
  if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);}
}