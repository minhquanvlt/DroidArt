package com.cleveroad.droidwordart.screens.main.pick_image


import android.Manifest
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.Snackbar
import android.support.v4.content.ContextCompat
import android.support.v4.content.FileProvider
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import com.cleveroad.bootstrap.base.IBackPressable
import com.cleveroad.bootstrap.kotlin_core.utils.FileUtils
import com.cleveroad.bootstrap.kotlin_core.utils.ImageUtils
import com.cleveroad.bootstrap.kotlin_core.utils.ImageUtils.compressImageFromUri
import com.cleveroad.bootstrap.kotlin_core.utils.ImageUtils.modifyImageToNormalOrientation
import com.cleveroad.bootstrap.kotlin_core.utils.ImageUtils.saveBitmap
import com.cleveroad.bootstrap.kotlin_core.utils.misc.MiscellaneousUtils
import com.cleveroad.bootstrap.kotlin_core.utils.withNotNull
import com.cleveroad.bootstrap.kotlin_permissionrequest.PermissionRequest
import com.cleveroad.bootstrap.kotlin_permissionrequest.PermissionResult
import com.cleveroad.colorpicker.CircleProperty
import com.cleveroad.colorpicker.ColorPickerAdapter
import com.cleveroad.colorpicker.OnSelectedColorListener
import com.cleveroad.droidwordart.BuildConfig
import com.cleveroad.droidwordart.R
import com.cleveroad.droidwordart.R.array.material_colors
import com.cleveroad.droidwordart.models.CloseAppType
import com.cleveroad.droidwordart.models.DisplayMode
import com.cleveroad.droidwordart.models.PickImageType
import com.cleveroad.droidwordart.screens.base.BSFragment
import com.cleveroad.droidwordart.screens.base.BSFragment.RequestCode.*
import com.cleveroad.droidwordart.screens.main.OnStartActivityForResultListener
import com.cleveroad.droidwordart.screens.main.create_word_dialog.CreateWordDialogFragment
import com.cleveroad.droidwordart.screens.main.create_word_dialog.CreateWordDialogFragment.Companion.WORD_COLOR_EXTRA
import com.cleveroad.droidwordart.screens.main.create_word_dialog.CreateWordDialogFragment.Companion.WORD_EXTRA
import com.cleveroad.droidwordart.screens.main.pick_image_dialog.PickImageDialogFragment
import com.cleveroad.droidwordart.screens.main.remove_image_dialog.RemoveImageDialogFragment
import com.cleveroad.droidwordart.utils.invisible
import com.cleveroad.droidwordart.utils.loadImage
import com.cleveroad.droidwordart.utils.visible
import com.cleveroad.wordart.*
import kotlinx.android.synthetic.main.bottom_sheet_word_settings.*
import kotlinx.android.synthetic.main.fragment_pick_image.*
import java.io.File
import java.lang.ref.WeakReference
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

class PickImageFragment : BSFragment<PickImagePresenter>(),
        PickImageView,
        View.OnClickListener,
        DialogInterface.OnClickListener,
        OnSelectedColorListener,
        SeekBar.OnSeekBarChangeListener,
        IBackPressable {

    override val containerId: Int
        get() = R.layout.fragment_pick_image

    companion object {
        private const val MAX_SIZE_IMAGE = 1024.0 * 1024.0 * 5
        private const val DEFAULT_BLUR_RADIUS = 1F
        private const val DEFAULT_PROGRESS = 0
        private const val START_PROGRESS = 0
        private const val PROGRESS_BLUR_FORMAT = "00"
        private const val PROGRESS_SHADOW_FORMAT = "%d%s"
        private const val PROGRESS_SHADOW_UNIT = "%"
        private const val DEFAULT_TEXT = ""
        private const val IMAGE_MIME_TYPE = "image/*"
        private const val DEFAULT_DATE_PATTERN = "yyyyMMdd_HHmmss"
        private const val IMAGE_FORMAT = ".png"
        private const val SELECTOR_COLOR_DEFAULT = Color.WHITE
        private const val SELECTOR_BUTTON_COLOR_DEFAULT = Color.WHITE
        private const val OFFSET_ITEM_POSITION = 1
        private const val DASH_PATH_ON_DISTANCE = 30F
        private const val DASH_PATH_OFF_DISTANCE = 10F
        private const val DASH_PATH_PHASE = 0F
        private const val DEFAULT_LEFT_TOP_ANGLE = 0F
        private const val STROKE_WIDTH_FOR_DASH_LINE = 4F
        private val CURRENT_PHOTO_PATH_EXTRA = MiscellaneousUtils.getExtra("CURRENT_PHOTO_PATH", PickImageDialogFragment::class.java)
        private val CURRENT_DISPLAY_MODE_EXTRA = MiscellaneousUtils.getExtra("CURRENT_DISPLAY_MODE", PickImageDialogFragment::class.java)

        fun newInstance(): PickImageFragment = PickImageFragment().apply {
            arguments = Bundle()
        }
    }

    private val permissionRequest: PermissionRequest = PermissionRequest()
    private var startActivityForResultListener: OnStartActivityForResultListener? = null
    private var currentPhotoPath: String? = null
    private var isInitEditorView = true
    private lateinit var wordSettingsBottomSheet: BottomSheetBehavior<LinearLayout>
    private var colorPickerAdapter: ColorPickerAdapter? = null
    private var currentDisplayMode: DisplayMode = DisplayMode.MODE_PICK_IMAGE
    private val touchEventCallback = object : TouchEventCallback {
        override fun touchEvent(changeText: ChangeText, selectorPosition: SelectorPosition) {
            updateView(when (changeText) {
                ChangeText.OFF_CHANGE_VIEW_TEXT -> when (selectorPosition) {
                    SelectorPosition.INSIDE_SELECTOR -> DisplayMode.MODE_ON_EDIT_STYLE
                    else -> DisplayMode.MODE_PREVIEW_WORD
                }
                ChangeText.ON_CHANGE_VIEW_TEXT -> DisplayMode.MODE_OFF_EDIT_STYLE
            })
        }

        override fun longTouchEvent(changeText: ChangeText, selectorPosition: SelectorPosition) {
            if (changeText == ChangeText.OFF_CHANGE_VIEW_TEXT && currentDisplayMode != DisplayMode.MODE_ON_EDIT_STYLE) {
                RemoveImageDialogFragment.newInstance(this@PickImageFragment, REQUEST_DIALOG_REMOVE_IMAGE())
                        .show(fragmentManager, RemoveImageDialogFragment::class.java.simpleName)
            }
        }

    }
    private var evWordArt: EditorView? = null
    private val fonts = FontsFactory.getFonts()
    private var closeAppType = CloseAppType.NEUTRAL_CLOSE_APP

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        startActivityForResultListener = bindInterfaceOrThrow<OnStartActivityForResultListener>(this, context)
    }

    override fun init() = initPresenter(LoaderId.PICK_IMAGE(), this, { PickImagePresenterImpl() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setClickListeners(this, bPickImageFirst, ivPickImage, fabAddWordArt, tvEditText, tvExport, tvResetText)

        savedInstanceState?.let {
            currentPhotoPath = it.getString(CURRENT_PHOTO_PATH_EXTRA)
            currentDisplayMode = it.getSerializable(CURRENT_DISPLAY_MODE_EXTRA) as DisplayMode
            pickImageFromCameraResult()
            isInitEditorView = !isInitEditorView
        }

        wordSettingsBottomSheet = BottomSheetBehavior.from(llWordSettings)
        wordSettingsBottomSheet.setBottomSheetCallback(object : BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    updateView(DisplayMode.MODE_PREVIEW_WORD)
                }
            }
        })
        wordSettingsBottomSheet.state = BottomSheetBehavior.STATE_HIDDEN

        context?.let { context ->
            rvColorPicker.layoutManager =
                    LinearLayoutManager(
                            context,
                            LinearLayoutManager.HORIZONTAL,
                            false
                    ).apply { scrollToPositionWithOffset(OFFSET_ITEM_POSITION, resources.getDimensionPixelOffset(R.dimen.item_offset)) }
            colorPickerAdapter = ColorPickerAdapter(
                    context,
                    context.resources
                            .getIntArray(material_colors)
                            .map { CircleProperty(it, ContextCompat.getColor(context, R.color.manatee_color_border)) },
                    WeakReference(this))
            rvColorPicker.adapter = colorPickerAdapter
        }

        initSeekBar()
        initFontsSelector()

        updateView(currentDisplayMode)
    }

    override fun onDestroyView() {
        evWordArt?.unsubscribeTouchEventCallback(touchEventCallback)
        super.onDestroyView()
    }

    override fun onDetach() {
        startActivityForResultListener = null
        super.onDetach()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        with(outState) {
            super.onSaveInstanceState(this)
            putString(CURRENT_PHOTO_PATH_EXTRA, currentPhotoPath)
            putSerializable(CURRENT_DISPLAY_MODE_EXTRA, currentDisplayMode)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_DIALOG_PICK_IMAGE() ->
                    data?.let { dialogPickImageResult(data.getSerializableExtra(PickImageDialogFragment.PICK_IMAGE_EXTRA) as PickImageType) }
                REQUEST_PICK_IMAGE_FROM_GALLERY() -> data?.let { pickImageFromGalleryResult(data.data) }
                REQUEST_PICK_IMAGE_FROM_CAMERA() -> pickImageFromCameraResult()
                REQUEST_CREATE_WORD() -> {
                    initWordArt()
                    updateView(DisplayMode.MODE_PREVIEW_WORD)
                    data?.let { createWordResult(it.getStringExtra(WORD_EXTRA), it.getIntExtra(WORD_COLOR_EXTRA, Color.WHITE)) }
                }
                REQUEST_DIALOG_REMOVE_IMAGE() -> updateView(DisplayMode.MODE_PICK_IMAGE)
            }
        }
        if (resultCode == Activity.RESULT_CANCELED && requestCode == REQUEST_CREATE_WORD()) updateView(DisplayMode.MODE_PREVIEW_WORD)
    }

    override fun onSelectedColor(color: Int) {
        evWordArt?.textColor = color
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.bPickImageFirst, R.id.ivPickImage ->
                PickImageDialogFragment.newInstance(this, REQUEST_DIALOG_PICK_IMAGE())
                        .show(fragmentManager, PickImageDialogFragment::class.java.simpleName)
            R.id.fabAddWordArt -> {
                updateView(DisplayMode.MODE_CREATE_WORD)
                CreateWordDialogFragment.newInstance(this, REQUEST_CREATE_WORD())
                        .show(fragmentManager, CreateWordDialogFragment::class.java.simpleName)
            }
            R.id.tvEditText -> {
                updateView(DisplayMode.MODE_EDIT_WORD)
                CreateWordDialogFragment.newInstance(this, REQUEST_CREATE_WORD(), evWordArt?.text
                        ?: DEFAULT_TEXT, evWordArt?.textColor ?: Color.WHITE)
                        .show(fragmentManager, CreateWordDialogFragment::class.java.simpleName)
            }
            R.id.tvExport -> saveImage()
            R.id.tvResetText -> evWordArt?.resetViewText()
        }
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        when (which) {
            Dialog.BUTTON_POSITIVE -> {
                closeAppType = CloseAppType.CLOSE_APP
                activity?.onBackPressed()
            }
            Dialog.BUTTON_NEGATIVE -> CloseAppType.NOT_CLOSE_APP
        }
    }

    override fun onBackPressed(): Boolean {
        return when {
        // when the text is selected
            currentDisplayMode == DisplayMode.MODE_ON_EDIT_STYLE -> {
                updateView(DisplayMode.MODE_PREVIEW_WORD)
                true
            }
        // when a positive button is selected in the dialog
            closeAppType == CloseAppType.CLOSE_APP -> false
        // when a negative button is selected in the dialog
            closeAppType == CloseAppType.NOT_CLOSE_APP -> {
                closeAppType = CloseAppType.NEUTRAL_CLOSE_APP
                true
            }
            else -> {
                showCloseAppDialog()
                true
            }
        }
    }

    private fun showCloseAppDialog() {
        context?.let {
            AlertDialog.Builder(it)
                    .setMessage(R.string.close_app_dialog_message)
                    .setPositiveButton(R.string.text_positive_button, this)
                    .setNegativeButton(R.string.text_negative_button, this)
                    .create()
                    .show()
        }
    }

    private fun dialogPickImageResult(type: PickImageType) {
        when (type) {
            PickImageType.GALLERY -> pickImageFromGallery()
            PickImageType.CAMERA -> pickImageFromCamera()
        }
    }

    private fun pickImageFromGalleryResult(uri: Uri) =
            context?.let { FileUtils.getSmartFilePath(it, uri) }?.let { pickImage(it) }

    private fun pickImageFromCameraResult() =
            withNotNull(currentPhotoPath) { pickImage(this) }

    private fun pickImage(imagePath: String) {
        if (imagePath.isNotEmpty()) {
            compressImageFromUri(Uri.fromFile(File(imagePath)), maxSize = MAX_SIZE_IMAGE)?.let {
                File(imagePath).takeIf { file -> saveBitmap(file, modifyImageToNormalOrientation(it, imagePath)) }
            }?.let {
                ivSelectedImage.loadImage(it.path)
                updateView(DisplayMode.MODE_PREVIEW_IMAGE)
            }
        }
    }

    private fun createWordResult(word: String, colorWord: Int) {
        evWordArt?.apply {
            text = word
            textColor = colorWord
            visible()
        }
    }

    private fun pickImageFromCamera() {
        permissionRequest.request(this, RequestCode.REQUEST_CAMERA(), arrayOf(Manifest.permission.CAMERA), object : PermissionResult {
            override fun onPermissionGranted() {
                context?.let {
                    ImageUtils.createImagePickIntentFromCamera(
                            it,
                            { ImageUtils.createImageFileTemp(it, false).also { currentPhotoPath = it.absolutePath } },
                            BuildConfig.FILEPROVIDER_NAME)?.let {
                        startActivityForResultListener?.onStartActivityForResult(it, RequestCode.REQUEST_PICK_IMAGE_FROM_CAMERA())
                    }
                }
            }
        })
    }

    private fun pickImageFromGallery() {
        permissionRequest.request(this, RequestCode.REQUEST_WRITE_EXTERNAL_STORAGE(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), object : PermissionResult {
            override fun onPermissionGranted() {
                context?.let {
                    startActivityForResultListener?.onStartActivityForResult(ImageUtils.createImagePickIntentFromGallery(it), RequestCode.REQUEST_PICK_IMAGE_FROM_GALLERY())
                }
            }
        })
    }

    private fun saveImage() {
        permissionRequest.request(this, RequestCode.REQUEST_EXPORT_IMAGE(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), object : PermissionResult {
            override fun onPermissionGranted() {
                saveImageToGallery()?.let { showSnackbar(it) }
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionRequest.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        seekBar?.let {
            when (it.id) {
                R.id.sbShadow -> {
                    tvShadow.text = String.format(PROGRESS_SHADOW_FORMAT, progress, PROGRESS_SHADOW_UNIT)
                    evWordArt?.textElevationPercent = progress
                }
                R.id.sbShadowBlur -> {
                    tvShadowBlur.text = DecimalFormat(PROGRESS_BLUR_FORMAT).format(progress)
                    evWordArt?.textShadowBlurRadius = if (progress == START_PROGRESS) DEFAULT_BLUR_RADIUS else progress.toFloat()
                }
            }
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        // do nothing
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        // do nothing
    }

    private fun initWordArt() {
        if (!isInitEditorView) {
            val lParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)

            evWordArt = context?.let { EditorView(it) }

            evWordArt?.apply {
                setPathEffectForSelector(
                        DashPathEffect(floatArrayOf(DASH_PATH_ON_DISTANCE, DASH_PATH_OFF_DISTANCE), DASH_PATH_PHASE))
                setStrokeWidthForDashLine(STROKE_WIDTH_FOR_DASH_LINE)
                setColorForTextShadow(Color.GRAY)
                setColorForSelectorButton(SELECTOR_BUTTON_COLOR_DEFAULT)
                setColorForDashLine(SELECTOR_BUTTON_COLOR_DEFAULT)
                showScaleRotateButton(ShowButtonOnSelector.HIDE_BUTTON)
                showResetViewTextButton(ShowButtonOnSelector.HIDE_BUTTON)
                subscribeTouchEventCallback(touchEventCallback)
            }

            evLayout.addView(evWordArt, lParams)
            isInitEditorView = !isInitEditorView
        }
    }

    private fun initSeekBar() {
        sbShadow.setOnSeekBarChangeListener(this)
        sbShadowBlur.setOnSeekBarChangeListener(this)

        sbShadow.progress = DEFAULT_PROGRESS
        sbShadowBlur.progress = DEFAULT_PROGRESS
    }

    private fun initFontsSelector() {
        spFonts.adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, resources.getStringArray(R.array.fonts))
        spFonts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                evWordArt?.fontId = fonts[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // do nothing
            }
        }
    }

    private fun saveImageToGallery(): File? {
        var file: File? = null
        context?.let {
            createImageFileTemp(it).also { fileTemp ->
                val bitmapDrawable = (ivSelectedImage.drawable as BitmapDrawable).bitmap
                saveBitmap(fileTemp, evWordArt?.saveResult(bitmapDrawable)
                        ?: saveResult(bitmapDrawable))
                ContentValues().apply {
                    put(Images.Media.DATE_TAKEN, System.currentTimeMillis())
                    put(MediaStore.MediaColumns.DATA, fileTemp.absolutePath)
                    it.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, this)
                }
                file = fileTemp
            }
        }
        return file
    }

    private fun createImageFileTemp(context: Context): File {
        val timeStamp = SimpleDateFormat(DEFAULT_DATE_PATTERN, Locale.getDefault()).format(Date())
        val storageDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), context.getString(R.string.app_name)).apply {
            if (!exists()) mkdir()
        }
        return File.createTempFile(timeStamp, IMAGE_FORMAT, storageDir)
    }

    private fun saveResult(bitmap: Bitmap): Bitmap {
        val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(resultBitmap).drawBitmap(bitmap, DEFAULT_LEFT_TOP_ANGLE, DEFAULT_LEFT_TOP_ANGLE, Paint())
        return resultBitmap
    }

    private fun showSnackbar(photoFile: File) {
        view?.let {
            Snackbar.make(it, R.string.Image_has_been_exported_to_gallery, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.preview, { onActionClick(photoFile) })
                    .setDuration(Snackbar.LENGTH_LONG)
                    .show()
        }
    }

    private fun onActionClick(photoFile: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context?.let {
                intent.setDataAndType(FileProvider.getUriForFile(it, BuildConfig.FILEPROVIDER_NAME, photoFile), IMAGE_MIME_TYPE)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            intent.setDataAndType(Uri.fromFile(photoFile), IMAGE_MIME_TYPE)
        }
        startActivity(intent)
    }

    private fun updateView(displayMode: DisplayMode) {
        when (displayMode) {
            DisplayMode.MODE_PICK_IMAGE -> {
                showViews(llImageNotLoad, bPickImageFirst)
                wordSettingsBottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                ivSelectedImage.setImageDrawable(null)
                hideViews(ivPickImage, tvExport, ivSelectedImage, fabAddWordArt)
                evLayout.removeAllViews()
                isInitEditorView = !isInitEditorView
            }
            DisplayMode.MODE_PREVIEW_IMAGE -> {
                showViews(ivPickImage, tvExport, ivSelectedImage, fabAddWordArt)
                hideViews(llImageNotLoad, bPickImageFirst)
            }
            DisplayMode.MODE_CREATE_WORD -> hideViews(ivPickImage, tvExport, fabAddWordArt)
            DisplayMode.MODE_PREVIEW_WORD -> {
                if (currentDisplayMode != DisplayMode.MODE_EDIT_WORD) {
                    showViews(ivPickImage, tvExport, fabAddWordArt)
                    hideViews(tvResetText)
                }
                wordSettingsBottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                hideViews(tvEditText)
                hideButtonAndFrame()
            }
            DisplayMode.MODE_ON_EDIT_STYLE -> {
                wordSettingsBottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                evWordArt?.setBitmapChangeViewTextButton(R.drawable.ic_curve_black_24dp)
                showViews(tvEditText)
                hideViews(ivPickImage, tvExport, tvResetText)
                showButtonAndFrame()
            }
            DisplayMode.MODE_OFF_EDIT_STYLE -> {
                evWordArt?.setBitmapChangeViewTextButton(R.drawable.ic_check_black_24dp)
                showViews(tvResetText)
            }
            DisplayMode.MODE_EDIT_WORD -> {
                hideViews(tvEditText, fabAddWordArt)
                wordSettingsBottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                hideButtonAndFrame()
            }
        }
        currentDisplayMode = displayMode
    }

    private fun showButtonAndFrame() {
        evWordArt?.apply {
            showChangeViewTextButton(ShowButtonOnSelector.SHOW_BUTTON)
            setColorForSelector(SELECTOR_COLOR_DEFAULT)
        }
    }

    private fun hideButtonAndFrame() {
        evWordArt?.apply {
            if (getChangeViewTextMode() == ChangeText.ON_CHANGE_VIEW_TEXT) changeViewTextMode(ChangeText.OFF_CHANGE_VIEW_TEXT)
            showChangeViewTextButton(ShowButtonOnSelector.HIDE_BUTTON)
            setColorForSelector(Color.TRANSPARENT)
        }
    }

    private fun showViews(vararg views: View) = views.forEach { it.visible() }

    private fun hideViews(vararg views: View) = views.forEach { it.invisible() }
}