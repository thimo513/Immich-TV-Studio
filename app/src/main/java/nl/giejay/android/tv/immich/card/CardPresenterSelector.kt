package nl.giejay.android.tv.immich.card

import android.content.Context
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector

class CardPresenterSelector(val context: Context): PresenterSelector() {
    // Leanback compares presenters by identity: one shared instance lets the grid recycle card
    // views (a new presenter per item created a new view type for every card)
    private val presenter by lazy { CardPresenter(context) }

    override fun getPresenter(item: Any?): Presenter {
        return presenter
    }

    override fun getPresenters(): Array<Presenter> = arrayOf(presenter)
}