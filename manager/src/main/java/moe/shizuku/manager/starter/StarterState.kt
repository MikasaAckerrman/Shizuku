package moe.shizuku.manager.starter

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Tracks whether a Shizuku start is currently in progress. Used by the home
 * screen start button to disable itself while a start is running and avoid
 * accidental double-starts.
 */
object StarterState {
    private val _isStarting = MutableLiveData(false)
    val isStarting: LiveData<Boolean> = _isStarting

    fun setStarting(starting: Boolean) {
        _isStarting.postValue(starting)
    }
}
