package fr.leslapibreizh.mediatheque

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w432dp-h768dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceScreensTest {
    private fun call(a:MainActivity,name:String,vararg args:Any):Any? {
        val m=a.javaClass.declaredMethods.first{it.name==name && it.parameterCount==args.size};m.isAccessible=true;return m.invoke(a,*args)
    }
    private fun set(a:MainActivity,name:String,value:Any){a.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(a,value)}
    private fun texts(v:View):List<String> = (if(v is TextView)listOf(v.text.toString())else emptyList()) +
        (if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))}else emptyList())
    private fun render(a:MainActivity,name:String):View {
        Thread.sleep(150);Shadows.shadowOf(Looper.getMainLooper()).idle()
        val host=a.findViewById<ViewGroup>(android.R.id.content)
        val page=host.getChildAt(0)
        page.measure(View.MeasureSpec.makeMeasureSpec(864,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1536,View.MeasureSpec.EXACTLY));page.layout(0,0,864,1536)
        val image=Bitmap.createBitmap(864,1536,Bitmap.Config.ARGB_8888);page.draw(Canvas(image))
        val folder=File(System.getProperty("java.io.tmpdir"),"lapibreizh-qa").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        fun clipping(v:View):List<String> {
            if(v.visibility!=View.VISIBLE)return emptyList()
            val own=if(v is TextView && v.layout!=null && v.layout.height>v.height-v.paddingTop-v.paddingBottom+2) listOf("${v.text}: layout ${v.layout.height}, box ${v.height}")else emptyList()
            return own+(if(v is ViewGroup)(0 until v.childCount).flatMap{clipping(v.getChildAt(it))}else emptyList())
        }
        File(folder,"$name-layout.txt").writeText(clipping(page).joinToString("\n"))
        assertEquals(864,page.width);assertEquals(1536,page.height)
        return page
    }
    private fun media(id:Long):Any {
        val c=Class.forName("fr.leslapibreizh.mediatheque.MainActivity\$Media").declaredConstructors.first{it.parameterCount==7};c.isAccessible=true
        return c.newInstance(id,Uri.parse("content://media/external/images/media/$id"),"image/jpeg","Photo-$id.jpg","Album test",1L,200L)
    }
    @Test fun catalogsCategoriesAndManagementRenderWithoutCrashing(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        Shadows.shadowOf(a.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val prefs=a.getSharedPreferences("lapibreizh_categories_v02",0)
        prefs.edit().putString("category_names",(1..8).joinToString("\u001f"){"Catégorie $it"}).commit()
        call(a,"showCategoryCatalog",false);render(a,"01-images-catalog")
        call(a,"showCategoryCatalog",true);assertTrue(texts(render(a,"02-videos-catalog")).any{it.contains("vidéo")})
        val route=Class.forName("fr.leslapibreizh.mediatheque.MainActivity\$Route")
        for(video in listOf(false,true)){
            set(a,"pendingCategory","Catégorie 1")
            call(a,"showGallery",route.enumConstants.first{it.toString()==if(video)"VIDEOS" else  "IMAGES"})
            call(a,"updateItems")
            val t=texts(render(a,if(video)"04-video-empty" else  "03-image-empty"))
            assertTrue(t.any{it==if(video)"Aucune vidéo dans cette catégorie" else  "Aucune image dans cette catégorie"})
        }
        call(a,"showCategoryEditor","Catégorie 1");assertTrue(texts(render(a,"05-category-editor")).contains("Enregistrer"))
        call(a,"showAlbumImport",false);render(a,"06-album-import")
        set(a,"duplicatePairs",listOf(Pair(media(1),media(2))));call(a,"showDuplicateComparison");render(a,"07-duplicate")
        call(a,"showMoveProgress",listOf(media(1)),"Catégorie 2");render(a,"08-move-result")
        assertEquals("Catégorie 2",prefs.getString("media_i:1",null))
        controller.pause().stop().destroy()
    }
    @Test fun renamePreservesMediaAndOrderAndDeletionKeepsFiles(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        val p=a.getSharedPreferences("lapibreizh_categories_v02",0)
        p.edit().putString("category_names","Ancien").putString("media_i:42","Ancien").putString("media_order_Ancien","i:42").commit()
        assertEquals(true,call(a,"saveCategoryChanges","Ancien","Nouveau","★"))
        assertEquals("Nouveau",p.getString("media_i:42",null));assertEquals("i:42",p.getString("media_order_Nouveau",null))
        call(a,"deleteCategoryMetadata","Nouveau",true)
        assertEquals("Autres",p.getString("media_i:42",null));assertTrue(p.getString("category_names","")!!.contains("Autres"))
        controller.pause().stop().destroy()
    }
}
