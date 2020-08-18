/*
 * Copyright 2020 Paul Rybitskyi, paul.rybitskyi.work@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("ValuePickerViewUtils")

package com.paulrybitskyi.valuepicker

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.content.withStyledAttributes
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.paulrybitskyi.commons.ktx.*
import com.paulrybitskyi.commons.ktx.drawing.getTextBounds
import com.paulrybitskyi.commons.recyclerview.utils.disableAnimations
import com.paulrybitskyi.commons.recyclerview.utils.recreateItemViews
import com.paulrybitskyi.commons.utils.observeChanges
import com.paulrybitskyi.valuepicker.decorators.ValuePickerItemDecorator
import com.paulrybitskyi.valuepicker.decorators.ValuePickerItemDecoratorFactory.createHorizontalDecorator
import com.paulrybitskyi.valuepicker.decorators.ValuePickerItemDecoratorFactory.createVerticalDecorator
import com.paulrybitskyi.valuepicker.layoutmanager.ValuePickerLayoutManager
import com.paulrybitskyi.valuepicker.model.*
import com.paulrybitskyi.valuepicker.model.Orientation.Companion.asOrientation
import com.paulrybitskyi.valuepicker.scrollerhelpers.ScrollerHelper
import com.paulrybitskyi.valuepicker.scrollerhelpers.ScrollerHelperFactory
import com.paulrybitskyi.valuepicker.utils.getColor
import com.paulrybitskyi.valuepicker.valueeffects.ValueEffect
import com.paulrybitskyi.valuepicker.valueeffects.concrete.AlphaValueEffect
import java.util.*
import com.paulrybitskyi.valuepicker.model.Orientation as PickerOrientation


private const val DEFAULT_MAX_VISIBLE_ITEMS = 3

private const val DEFAULT_FLING_SPEED_FACTOR_MIN = 0.1f
private const val DEFAULT_FLING_SPEED_FACTOR_MAX = 1f
private const val DEFAULT_FLING_SPEED_FACTOR = 0.3f

private val DEFAULT_VALUE_EFFECT = AlphaValueEffect()


class ValuePickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {


    @set:JvmName("setDividersEnabled")
    @get:JvmName("areDividersEnabled")
    var areDividersEnabled: Boolean by observeChanges(true) { _, _ ->
        initValuePickerItemDecorator()
    }

    var isInfiniteScrollEnabled: Boolean by observeChanges(false) { _, _ ->
        valuePickerAdapter.scrollerHelper = initScrollerHelper()
        recreateItemViews()
    }

    var isScrollingDisabled: Boolean = false

    private val hasItems: Boolean
        get() = _items.isNotEmpty()

    private val hasFixedItemSize: Boolean
        get() = (fixedItemSize?.hasBothDimensions == true)

    private val hasFixedItemWidth: Boolean
        get() = (fixedItemSize?.hasWidth == true)

    private val hasFixedItemHeight: Boolean
        get() = (fixedItemSize?.hasHeight == true)

    private val itemCount: Int
        get() = _items.size

    var maxVisibleItems: Int = DEFAULT_MAX_VISIBLE_ITEMS
        set(value) {
            require(value.isOdd) { "The max visible items value must be odd." }
            field = value
            initValuePickerItemDecorator()
            recalculateRecyclerViewSizing()
        }

    @get:ColorInt
    var textColor: Int
        set(@ColorInt value) {
            valueItemViewPaint.color = value
            updateValueItemConfig(valueItemConfig.copy(textColor = value))
        }
        get() = valueItemConfig.textColor

    @get:ColorInt
    var dividerColor: Int? by observeChanges(null) { _, _ ->
        dividerDrawable = dividerDrawable
    }

    var flingSpeedFactor: Float = DEFAULT_FLING_SPEED_FACTOR
        set(value) {
            field = value.coerceIn(
                DEFAULT_FLING_SPEED_FACTOR_MIN,
                DEFAULT_FLING_SPEED_FACTOR_MAX
            )
        }

    var textSize: Float
        set(value) {
            valueItemViewPaint.textSize = value
            updateValueItemConfig(valueItemConfig.copy(textSize = value))
        }
        get() = valueItemConfig.textSize

    var textTypeface: Typeface
        set(value) {
            valueItemViewPaint.typeface = value
            updateValueItemConfig(valueItemConfig.copy(textTypeface = value))
        }
        get() = valueItemConfig.textTypeface

    private var _selectedItem: Item? = null

    val selectedItem: Item?
        get() = _selectedItem

    var fixedItemSize: Size? by observeChanges(null) { _, _ ->
        recalculateValueItemSize()
        recalculateRecyclerViewSizing()
    }

    private val defaultValues = initDefaultValues(context)

    private var valueItemConfig by observeChanges(VALUE_ITEM_CONFIG_STUB) { _, config ->
        valuePickerAdapter.valueItemConfig = config
        recreateItemViews()
    }

    private var _items by observeChanges<List<Item>>(listOf()) { _, items ->
        recalculateValueItemSize()
        recalculateMaxVisibleItems(itemCount)
        recalculateRecyclerViewSizing()
        valuePickerAdapter.items = items
        scrollToSelectedItem()
    }

    var items: List<Item>
        set(value) {
            require(value.size > 1) { "The item list must contain at least two items." }
            _items = value.toList()
        }
        get() = Collections.unmodifiableList(_items)

    private lateinit var valueItemViewPaint: Paint
    private val valueItemViewTextBounds = Rect()

    var valueEffect: ValueEffect by observeChanges(DEFAULT_VALUE_EFFECT) { _, _ ->
        initLayoutManager()
        scrollToSelectedItem()
    }

    private var valuePickerItemDecorator: ValuePickerItemDecorator? = null

    private lateinit var valuePickerAdapter: ValuePickerRecyclerViewAdapter

    var dividerDrawable: Drawable? = getDrawable(R.drawable.value_picker_divider_drawable)
        set(value) {
            field = dividerColor?.let { value?.setColor(it) } ?: value
            areDividersEnabled = (field != null)
        }

    var orientation: PickerOrientation by observeChanges(PickerOrientation.VERTICAL) { _, _ ->
        initLayoutManager()
        recalculateRecyclerViewSizing()
        scrollToSelectedItem()
    }

    var onItemSelectedListener: OnItemSelectedListener? = null


    fun interface OnItemSelectedListener {

        fun onItemSelected(item: Item)

    }


    init {
        initRecyclerView()
        initValueItemConfig()
        initValueItemViewPaint()
        initDefaults()

        attrs?.let { extractAttributes(it, defStyleAttr) }
    }


    private fun initRecyclerView() {
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        disableAnimations()
        initValuePickerItemDecorator()
        initSnapHelper()
        initLayoutManager()
        initAdapter()
    }


    private fun initValuePickerItemDecorator() {
        if(!areDividersEnabled) {
            valuePickerItemDecorator?.let(::removeItemDecoration)
            return
        }

        createDecorator()
            .also(::addItemDecoration)
            .also {
                valuePickerItemDecorator?.let(::removeItemDecoration)
                valuePickerItemDecorator = it
            }
    }


    private fun createDecorator(): ValuePickerItemDecorator {
        val divider = requireNotNull(dividerDrawable)
        val valueItemConfigProvider: (() -> ValueItemConfig) = { valueItemConfig }

        return when(orientation) {
            PickerOrientation.VERTICAL -> createVerticalDecorator(maxVisibleItems, divider, valueItemConfigProvider)
            PickerOrientation.HORIZONTAL -> createHorizontalDecorator(maxVisibleItems, divider, valueItemConfigProvider)
        }
    }


    private fun initSnapHelper() {
        LinearSnapHelper().attachToRecyclerView(this)
    }


    private fun initLayoutManager() {
        ValuePickerLayoutManager(this, orientation, valueEffect)
            .apply { onViewSelectedListener = ::handleItemViewSelection }
            .also(::setLayoutManager)
    }


    private fun handleItemViewSelection(view: View) {
        val adapterPosition = getChildLayoutPosition(view)

        valuePickerAdapter.getItem(adapterPosition)?.let {
            setSelectedItem(
                item = it,
                scrollToPosition = false
            )
        }
    }


    private fun initAdapter() {
        ValuePickerRecyclerViewAdapter(
            items = _items,
            valueItemConfig = valueItemConfig,
            scrollerHelper = initScrollerHelper()
        )
        .apply { onItemClickListener = ::handleItemClick }
        .also {
            adapter = it
            valuePickerAdapter = it
        }
    }


    private fun initScrollerHelper(): ScrollerHelper {
        return ScrollerHelperFactory.create(
            isInfinite = isInfiniteScrollEnabled,
            dataSetItemCount = itemCount
        )
    }


    private fun handleItemClick(view: View) {
        smoothScrollToView(view)
    }


    private fun smoothScrollToView(view: View) {
        smoothScrollToPosition(getChildAdapterPosition(view))
    }


    private fun initValueItemConfig() {
        valueItemConfig = ValueItemConfig(
            size = defaultValues.valueItemSize,
            textColor = defaultValues.valueItemTextColor,
            textSize = defaultValues.valueItemTextSize,
            textTypeface = defaultValues.valueItemTextTypeface
        )
    }


    private fun initValueItemViewPaint() {
        valueItemViewPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = valueItemConfig.textColor
            textSize = valueItemConfig.textSize
            typeface = valueItemConfig.textTypeface
        }
    }


    private fun initDefaults() {
        areDividersEnabled = areDividersEnabled
        isInfiniteScrollEnabled = isInfiniteScrollEnabled
        maxVisibleItems = maxVisibleItems
        textColor = textColor
        dividerColor = dividerColor
        textSize = textSize
        textTypeface = textTypeface
        dividerDrawable = dividerDrawable
        orientation = orientation
    }


    private fun extractAttributes(attrs: AttributeSet, defStyle: Int) {
        context.withStyledAttributes(
            set = attrs,
            attrs = R.styleable.ValuePickerView,
            defStyleAttr = defStyle
        ) {
            areDividersEnabled = getBoolean(R.styleable.ValuePickerView_vpv_areDividersEnabled, areDividersEnabled)
            isInfiniteScrollEnabled = getBoolean(R.styleable.ValuePickerView_vpv_isInfiniteScrollEnabled, isInfiniteScrollEnabled)
            maxVisibleItems = getInteger(R.styleable.ValuePickerView_vpv_maxVisibleItems, maxVisibleItems)
            textColor = getColor(R.styleable.ValuePickerView_vpv_textColor, textColor)
            dividerColor = getColor(R.styleable.ValuePickerView_vpv_dividerColor, dividerColor)
            flingSpeedFactor = getFloat(R.styleable.ValuePickerView_vpv_flingSpeedFactor, flingSpeedFactor)
            textSize = getDimension(R.styleable.ValuePickerView_vpv_textSize, textSize)
            textTypeface = getFont(context, R.styleable.ValuePickerView_vpv_textTypeface, textTypeface)
            dividerDrawable = getDrawable(R.styleable.ValuePickerView_vpv_divider, dividerDrawable)
            orientation = getInt(R.styleable.ValuePickerView_vpv_orientation, orientation.id).asOrientation()
        }
    }


    private fun recalculateValueItemSize() {
        valueItemConfig = valueItemConfig.withRecalculatedItemSize()
    }


    private fun ValueItemConfig.withRecalculatedItemSize(): ValueItemConfig {
        return calculateValueItemSize().let {
            copy(size = sizeOf(it.width, it.height))
        }
    }


    private fun calculateValueItemSize(): Size {
        if(!hasItems) return defaultValues.valueItemSize
        if(hasFixedItemSize) return requireNotNull(fixedItemSize)

        val valueItemTextSizeRatio = (valueItemConfig.textSize / defaultValues.valueItemTextSize)
        val valueItemMeasuredSize = calculateValueItemMeasuredSize(valueItemTextSizeRatio)
        val valueItemMinSize = calculateValueItemMinSize(valueItemTextSizeRatio)

        return resolveValueItemSize(valueItemMeasuredSize, valueItemMinSize)
    }


    private fun calculateValueItemTextMaxSize(): Size {
        var valueItemTextMaxWidth = 0
        var valueItemTextMaxHeight = 0

        for(item in _items) {
            valueItemViewPaint.getTextBounds(item.title, valueItemViewTextBounds)

            val valueItemTextWidth = valueItemViewTextBounds.width()
            val valueItemTextHeight = valueItemViewTextBounds.height()

            if(valueItemTextMaxWidth < valueItemTextWidth) {
                valueItemTextMaxWidth = valueItemTextWidth
            }

            if(valueItemTextMaxHeight < valueItemTextHeight) {
                valueItemTextMaxHeight = valueItemTextHeight
            }
        }

        return sizeOf(
            width = valueItemTextMaxWidth,
            height = valueItemTextMaxHeight
        )
    }


    private fun calculateValueItemMeasuredSize(valueItemTextSizeRatio: Float): Size {
        val valueItemTextMaxSize = calculateValueItemTextMaxSize()
        val valueItemPadding = (defaultValues.valueItemPadding.toFloat() * valueItemTextSizeRatio).toInt()
        val dividerDrawableSize = getDividerDrawableSize()

        val width = (valueItemTextMaxSize.width + (valueItemPadding * 2) + (dividerDrawableSize.width * 2))
        val height = (valueItemTextMaxSize.height + (valueItemPadding * 2) + (dividerDrawableSize.height * 2))

        return sizeOf(width = width, height = height)
    }


    private fun getDividerDrawableSize(): Size {
        if(!areDividersEnabled) return sizeOf(width = 0, height = 0)

        val width = (if(orientation.isHorizontal) dividerDrawable?.intrinsicWidth else 0)
        val height = (if(orientation.isVertical) dividerDrawable?.intrinsicHeight else 0)

        return sizeOf(
            width = (width ?: 0),
            height = (height ?: 0)
        )
    }


    private fun calculateValueItemMinSize(valueItemTextSizeRatio: Float): Size {
        val minWidth = (defaultValues.valueItemMinWidth.toFloat() * valueItemTextSizeRatio).toInt()
        val minHeight = (defaultValues.valueItemMinHeight.toFloat() * valueItemTextSizeRatio).toInt()

        return sizeOf(width = minWidth, height = minHeight)
    }


    private fun resolveValueItemSize(measuredSize: Size, minSize: Size): Size {
        val finalWidth = resolveValueItemWidth(measuredSize.width, minSize.width)
        val finalHeight = resolveValueItemHeight(measuredSize.height, minSize.height)

        return sizeOf(width = finalWidth, height = finalHeight)
    }


    private fun resolveValueItemWidth(measuredWidth: Int, minWidth: Int): Int {
        if(hasFixedItemWidth) return requireNotNull(fixedItemSize).width
        if(measuredWidth < minWidth) return minWidth

        return measuredWidth
    }


    private fun resolveValueItemHeight(measuredHeight: Int, minHeight: Int): Int {
        if(hasFixedItemHeight) return requireNotNull(fixedItemSize).height
        if(measuredHeight < minHeight) return minHeight

        return measuredHeight
    }


    private fun updateValueItemConfig(newConfig: ValueItemConfig) {
        valueItemConfig = newConfig.withRecalculatedItemSize()
    }


    private fun recalculateMaxVisibleItems(itemSize: Int) {
        if(itemSize >= maxVisibleItems) return

        maxVisibleItems = when {
            (itemSize <= DEFAULT_MAX_VISIBLE_ITEMS) -> DEFAULT_MAX_VISIBLE_ITEMS
            itemSize.isOdd -> itemSize
            else -> (itemSize - 1)
        }
    }


    private fun recalculateRecyclerViewSizing() {
        when(orientation) {
            PickerOrientation.VERTICAL -> recalculateVerticalRecyclerViewSizing()
            PickerOrientation.HORIZONTAL -> recalculateHorizontalRecyclerViewSizing()
        }

        recreateItemViews()
    }


    private fun recalculateVerticalRecyclerViewSizing() {
        val valueItemViewHeight = valueItemConfig.size.height
        val rvHeight = (valueItemViewHeight * maxVisibleItems)
        val maxVisibleValuesFromCenter = (maxVisibleItems / 2)
        val verticalPadding = (valueItemViewHeight * maxVisibleValuesFromCenter)

        setLayoutParamsSize(ViewGroup.LayoutParams.WRAP_CONTENT, rvHeight)
        setVerticalPadding(verticalPadding)
        clearHorizontalPadding()
    }


    private fun recalculateHorizontalRecyclerViewSizing() {
        val valueItemViewWidth = valueItemConfig.size.width
        val rvWidth = (valueItemViewWidth * maxVisibleItems)
        val maxVisibleValuesFromCenter = (maxVisibleItems / 2)
        val horizontalPadding = (valueItemViewWidth * maxVisibleValuesFromCenter)

        setLayoutParamsSize(rvWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        setHorizontalPadding(horizontalPadding)
        clearVerticalPadding()
    }


    fun setSelectedItem(item: Item) {
        setSelectedItem(item, scrollToPosition = true)
    }


    private fun setSelectedItem(item: Item, scrollToPosition: Boolean) {
        _selectedItem = item

        if(scrollToPosition) {
            scrollToItem(item)
        }

        reportItemSelection(item)
    }


    private fun scrollToItem(item: Item) {
        valuePickerAdapter.getItemAdapterPosition(item)?.let(::scrollToPosition)
    }


    private fun reportItemSelection(item: Item) {
        onItemSelectedListener?.onItemSelected(item)
    }


    private fun scrollToSelectedItem() {
        if(_selectedItem != null) {
            scrollToItem(checkNotNull(_selectedItem))
            return
        }

        if(hasItems) {
            setSelectedItem(_items.first())
        }
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        return (isScrollingDisabled || super.onTouchEvent(event))
    }


    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return (isScrollingDisabled || super.onInterceptTouchEvent(event))
    }


    override fun fling(velocityX: Int, velocityY: Int): Boolean {
        return super.fling(
            (velocityX * flingSpeedFactor).toInt(),
            (velocityY * flingSpeedFactor).toInt()
        )
    }


}