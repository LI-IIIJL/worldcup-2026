package worldcup.helper.ui.teams

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import worldcup.helper.R
import worldcup.helper.data.CircleFlagLoader
import worldcup.helper.data.MatchData
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

class TeamsFragment : Fragment() {

    private lateinit var flagLoader: CircleFlagLoader
    private lateinit var teamsData: List<TeamInfo>
    private var allTeams: List<TeamInfo> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_teams, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        flagLoader = CircleFlagLoader(requireContext())
        loadTeamsData()

        val rv = view.findViewById<RecyclerView>(R.id.rv_teams)
        rv.layoutManager = GridLayoutManager(requireContext(), 3)
        val adapter = TeamAdapter(allTeams, flagLoader) { team ->
            val intent = Intent(requireContext(), TeamDetailActivity::class.java).apply {
                putExtra("team_name", team.nameEn)
            }
            startActivity(intent)
        }
        rv.adapter = adapter

        // Search
        val etSearch = view.findViewById<EditText>(R.id.et_search)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase()
                adapter.filter(if (query.isEmpty()) allTeams else {
                    allTeams.filter { it.nameCn.contains(query, true) || it.nameEn.contains(query, true) }
                })
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    data class TeamInfo(
        val nameEn: String,
        val nameCn: String,
        val countryCode: String,
        val group: String,
        val playerCount: Int
    )

    private fun loadTeamsData() {
        val teams = mutableListOf<TeamInfo>()
        try {
            val jsonStr = requireContext().assets.open("players_2026.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val arr = root.getJSONArray("teams")
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val nameEn = t.getString("name")
                val countryCode = t.getString("countryCode")
                val group = t.getString("group")
                val players = t.getJSONArray("players")
                val nameCn = MatchData.getChineseName(nameEn)
                teams.add(TeamInfo(nameEn, nameCn, countryCode, group, players.length()))
            }
        } catch (e: Exception) {
            android.util.Log.e("TeamsFragment", "Failed to load teams", e)
        }
        // 按中文名拼音排序
        val collator = Collator.getInstance(Locale.CHINESE)
        allTeams = teams.sortedWith(compareBy(collator) { it.nameCn })
    }
}

class TeamAdapter(
    private var teams: List<TeamsFragment.TeamInfo>,
    private val flagLoader: CircleFlagLoader,
    private val onClick: (TeamsFragment.TeamInfo) -> Unit
) : RecyclerView.Adapter<TeamAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivFlag: ImageView = view.findViewById(R.id.iv_team_flag)
        val tvName: TextView = view.findViewById(R.id.tv_team_name)
        val tvGroup: TextView = view.findViewById(R.id.tv_team_group)
        val tvCount: TextView = view.findViewById(R.id.tv_player_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_team, parent, false))
    }

    override fun getItemCount() = teams.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val team = teams[position]
        holder.tvName.text = team.nameCn
        holder.tvGroup.text = "${team.group}组"
        holder.tvCount.visibility = android.view.View.GONE

        val drawable = flagLoader.loadFlag(team.countryCode)
        if (drawable != null) {
            holder.ivFlag.setImageDrawable(drawable)
            holder.ivFlag.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        holder.itemView.setOnClickListener { onClick(team) }
    }

    fun filter(filtered: List<TeamsFragment.TeamInfo>) {
        teams = filtered
        notifyDataSetChanged()
    }
}
