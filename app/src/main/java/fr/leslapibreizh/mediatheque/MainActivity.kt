package fr.leslapibreizh.mediatheque
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
class MainActivity:AppCompatActivity(){
 private val gold=0xFFC9A54A.toInt()
 override fun onCreate(b:Bundle?){super.onCreate(b); val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,54,32,32);setBackgroundColor(0xFF080705.toInt())};
 root.addView(TextView(this).apply{text="Les Lapibreizh";textSize=36f;setTextColor(gold);gravity=17});root.addView(TextView(this).apply{text="LA MÉDIATHÈQUE\nImages • Vidéos • Créations";textSize=17f;setTextColor(0xFFF2E6C2.toInt());gravity=17;setPadding(0,16,0,48)});
 button(root,"MES IMAGES"){request(false)};button(root,"MONTAGES VIDÉO — EDITS"){request(true)};button(root,"À CLASSER"){toast("Espace À classer")};button(root,"PARAMÈTRES"){toast("Paramètres")};setContentView(root)}
 private fun button(r:LinearLayout,s:String,a:()->Unit){r.addView(Button(this).apply{text=s;textSize=16f;setOnClickListener{a()};layoutParams=LinearLayout.LayoutParams(-1,150).apply{setMargins(0,12,0,12)}})}
 private fun request(v:Boolean){val p=if(Build.VERSION.SDK_INT>=33) arrayOf(if(v) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE);val m=p.filter{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(m.isNotEmpty())ActivityCompat.requestPermissions(this,m.toTypedArray(),10)else toast(if(v)"Accès vidéos autorisé" else "Accès images autorisé")}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
