package io.relite.home.ui.drawer

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relite.home.R
import io.relite.home.ReliteHomeApplication
import io.relite.home.data.AppEntry
import io.relite.home.data.AppRepository
import io.relite.home.util.AppSearch

/**
 * Alphabetical, searchable app drawer. No network, no ads, no "recommended
 * apps" section — the drawer only ever shows what LauncherApps reports.
 */
class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {

    private lateinit var adapter: AppDrawerAdapter
    private var allApps: List<AppEntry> = emptyList()
    private var appsChangedSubscription: AppRepository.Subscription? = null

    var onLaunch: ((AppEntry) -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as ReliteHomeApplication

        adapter = AppDrawerAdapter(
            iconCache = app.iconCache,
            onAppClick = { onLaunch?.invoke(it) },
            onAppLongClick = { _, _ -> false },
        )

        val recycler = view.findViewById<RecyclerView>(R.id.drawer_recycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), COLUMN_COUNT)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

        allApps = AppSearch.alphabetical(app.appRepository.loadAll())
        adapter.submitList(allApps)

        appsChangedSubscription = app.appRepository.onAppsChanged {
            allApps = AppSearch.alphabetical(app.appRepository.loadAll())
            adapter.submitList(applyCurrentQuery())
        }

        val searchField = view.findViewById<EditText>(R.id.drawer_search)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                adapter.submitList(applyCurrentQuery())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private var currentQuery: String = ""

    override fun onDestroyView() {
        super.onDestroyView()
        appsChangedSubscription?.dispose()
        appsChangedSubscription = null
    }

    private fun applyCurrentQuery(): List<AppEntry> = AppSearch.search(allApps, currentQuery)

    companion object {
        private const val COLUMN_COUNT = 4
    }
}
