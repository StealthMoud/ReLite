package io.relite.home.ui.home

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class HomePagerAdapter(
    activity: FragmentActivity,
    private val pageCount: Int,
    private val configurePage: (HomePageFragment) -> Unit,
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = pageCount

    override fun createFragment(position: Int): Fragment =
        HomePageFragment.newInstance(position).also(configurePage)
}
