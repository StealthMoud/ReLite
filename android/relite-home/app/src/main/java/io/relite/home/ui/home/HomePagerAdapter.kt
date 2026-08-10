package io.relite.home.ui.home

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Long-lived: MainActivity creates exactly one instance and calls [update]
 * on every workspace edit, rather than assigning `pager.adapter` to a
 * brand-new [HomePagerAdapter] each time. Replacing the adapter object
 * itself on every edit was the original approach, and on a real device
 * (RMX5303) a burst of edits in quick succession reliably left stale
 * fragments from an earlier adapter instance still attached alongside the
 * new ones — duplicate, overlapping icon views on a page even though the
 * persisted workspace only had one item. [FragmentStateAdapter] expects to
 * own the fragment lifecycle across the adapter's own lifetime; swapping
 * the adapter object instead of updating it breaks that assumption.
 *
 * [getItemId]/[containsItem] fold a generation counter into each page's
 * id so that [update] (which bumps the generation and calls
 * `notifyDataSetChanged`) forces every currently-bound page fragment to be
 * torn down and recreated with fresh data, since [HomePageFragment] only
 * reads workspace state once, in `onViewCreated`.
 */
class HomePagerAdapter(
    activity: FragmentActivity,
    initialPageCount: Int,
) : FragmentStateAdapter(activity) {

    var pageCount: Int = initialPageCount
        private set
    private var generation = 0

    fun update(newPageCount: Int) {
        pageCount = newPageCount
        generation++
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pageCount

    override fun getItemId(position: Int): Long = position.toLong() * ID_MULTIPLIER + generation

    override fun containsItem(itemId: Long): Boolean {
        val position = (itemId / ID_MULTIPLIER).toInt()
        val itemGeneration = (itemId % ID_MULTIPLIER).toInt()
        return itemGeneration == generation && position in 0 until pageCount
    }

    override fun createFragment(position: Int): Fragment = HomePageFragment.newInstance(position)

    private companion object {
        const val ID_MULTIPLIER = 1_000_000L
    }
}
