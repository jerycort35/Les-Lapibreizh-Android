package fr.leslapibreizh.mediatheque

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.*
import android.widget.*

/** Reference coordinates are independent of Android dp and the user's font scale.
 * Only the central media viewport grows on taller phones. Text remains native. */
internal class ImagesReferenceLayout(context: Context, private val artResource: Int = R.drawable.images_master_art, private val baseHeight: Float = 1458f, private val serif: Boolean = true) : ViewGroup(context) {
    private data class Slot(val view: View, val x: Int, val y: Int, val w: Int, val h: Int, val bottom: Boolean, val stretch: Boolean)
    private val slots = mutableListOf<Slot>()
    private val fontSizes = mutableMapOf<TextView, Float>()
    private val gold = Color.rgb(233,192,102)
    private var unit = 1f
    private var logicalHeight = 1458f
    private val art by lazy { BitmapFactory.decodeResource(resources, artResource) }
    init { setBackgroundColor(Color.BLACK) }
    fun put(view: View, x: Int, y: Int, w: Int, h: Int, bottom: Boolean = false, stretch: Boolean = false): View {
        (view.parent as? ViewGroup)?.removeView(view)
        slots.add(Slot(view,x,y,w,h,bottom,stretch)); addView(view); return view
    }
    fun label(text: String, size: Float = 26f, color: Int = Color.WHITE, centered: Boolean = false): TextView = TextView(context).apply {
        this.text = text; setTextColor(color); typeface = Typeface.create(if(serif)"serif" else "sans-serif",Typeface.NORMAL)
        includeFontPadding = false; gravity = if(centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
        setPadding(0,0,0,0); fontSizes[this] = size
    }
    fun textSize(view: TextView, size: Float) { fontSizes[view]=size }
    fun border(glow: Boolean = false, red: Boolean = false): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if(red) intArrayOf(0xFF9C0714.toInt(),0xFFDF2133.toInt(),0xFF60000A.toInt())
        else if(glow) intArrayOf(0xFF22180A.toInt(),0xFF9D7120.toInt(),Color.BLACK)
        else intArrayOf(0xFF151515.toInt(),Color.BLACK)
    ).apply { cornerRadius=7*unit; setStroke(maxOf(1,(2*unit).toInt()),gold) }
    fun button(text: String, size: Float = 24f, glow: Boolean = false, red: Boolean = false, click: () -> Unit): TextView = label(text,size,Color.WHITE,true).apply {
        background=border(glow,red); isClickable=true; isFocusable=true; contentDescription=text
        setOnClickListener { click() }
    }
    fun crop(x:Int,y:Int,w:Int,h:Int,description:String,click:(()->Unit)?=null): View = object: View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            val sx=art.width/864f; val sy=art.height/1536f
            canvas.drawBitmap(art,Rect((x*sx).toInt(),(y*sy).toInt(),((x+w)*sx).toInt(),((y+h)*sy).toInt()),Rect(0,0,width,height),paint)
        }
    }.apply { contentDescription=description; if(click!=null){ isClickable=true; isFocusable=true; setOnClickListener { click() } } }
    fun videoGlyph(): View = object: View(context) {
        override fun onDraw(canvas:Canvas) {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=gold;style=Paint.Style.STROKE;strokeWidth=6f}
            canvas.save();canvas.scale(width/120f,height/100f);canvas.drawRoundRect(5f,5f,115f,95f,5f,5f,p)
            canvas.drawLine(25f,5f,25f,95f,p);canvas.drawLine(95f,5f,95f,95f,p)
            for(y in 25..80 step 25){canvas.drawLine(5f,y.toFloat(),25f,y.toFloat(),p);canvas.drawLine(95f,y.toFloat(),115f,y.toFloat(),p)}
            p.style=Paint.Style.FILL;canvas.drawPath(Path().apply{moveTo(46f,28f);lineTo(80f,50f);lineTo(46f,72f);close()},p);canvas.restore()
        }
    }.apply{contentDescription="Vidéo"}
    fun imageGlyph(): View = object: View(context) {
        override fun onDraw(canvas: Canvas) {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=gold;strokeWidth=5f;style=Paint.Style.STROKE}
            canvas.save();canvas.scale(width/120f,height/105f)
            canvas.drawRoundRect(5f,5f,115f,100f,7f,7f,p)
            p.style=Paint.Style.FILL;canvas.drawCircle(31f,30f,9f,p)
            val path=Path().apply{moveTo(8f,87f);lineTo(42f,49f);lineTo(62f,66f);lineTo(84f,38f);lineTo(112f,75f);lineTo(112f,97f);lineTo(8f,97f);close()}
            canvas.drawPath(path,p);canvas.restore()
        }
    }.apply{contentDescription="Image"}
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec); val h=MeasureSpec.getSize(heightMeasureSpec)
        unit=minOf(w/864f,h/baseHeight); logicalHeight=h/unit
        fontSizes.forEach { (v,size)->v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*unit) }
        slots.forEach { s ->
            val height=if(s.stretch) logicalHeight-s.y-s.h else s.h.toFloat()
            s.view.measure(MeasureSpec.makeMeasureSpec((s.w*unit).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((height*unit).toInt().coerceAtLeast(0),MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val left=(width-864*unit)/2
        slots.forEach { s ->
            val x=(left+s.x*unit).toInt(); val y=((if(s.bottom) logicalHeight+s.y else s.y.toFloat())*unit).toInt()
            s.view.layout(x,y,x+s.view.measuredWidth,y+s.view.measuredHeight)
        }
    }
}
