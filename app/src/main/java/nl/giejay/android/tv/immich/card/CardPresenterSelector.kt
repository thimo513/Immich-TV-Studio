package nl.giejay.android.tv.immich.card

import android.content.Context
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import nl.giejay.android.tv.immich.plus.ui.CardShape
import nl.giejay.android.tv.immich.plus.ui.StudioCardPresenter

class CardPresenterSelector(val context: Context, shape: CardShape = CardShape.WIDE): PresenterSelector() {
    // Leanback compares presenters by identity: one shared instance lets the grid recycle card
    // views (a new presenter per item created a new view type for every card)
    private val presenter: Presenter = StudioCardPresenter(shape)

    override fun getPresenter(item: Any?): Presenter {
        return presenter
    }

    override fun getPresenters(): Array<Presenter> = arrayOf(presenter)
}