package worldcup.helper.ui.ai

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import worldcup.helper.R
import worldcup.helper.databinding.FragmentAiChatBinding
import kotlinx.coroutines.launch

/**
 * Tab B: AI对话
 *
 * 功能：
 * - 聊天消息气泡展示（用户/ AI 双样式）
 * - 文本输入发送（支持 IME Send 键）
 * - 建议问题水平滚动栏
 * - 附件按钮：选择相册图片上传
 * - 自动滚动到最新消息
 */
class AiChatFragment : Fragment() {

    private var _binding: FragmentAiChatBinding? = null
    private val binding get() = _binding!!

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var chatAdapter: ChatAdapter
    private var layoutManager: LinearLayoutManager? = null

    // 图片选择器
    private val imagePickerLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { chatViewModel.addImageMessage(it.toString()) }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAiChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupInput()
        setupAttachButton()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 初始化 ====================

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()

        layoutManager = LinearLayoutManager(requireContext())
        binding.chatRecycler.apply {
            adapter = chatAdapter
            layoutManager = this@AiChatFragment.layoutManager
            setHasFixedSize(false)
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener { sendMessage() }

        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendMessage()
                true
            } else false
        }
    }

    private fun setupAttachButton() {
        binding.btnAttach.setOnClickListener {
            // 弹出选择：拍照或相册
            val options = arrayOf("📷 拍照", "🖼️ 从相册选择")
            AlertDialog.Builder(requireContext())
                .setTitle("上传图片")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> takePhoto()
                        1 -> pickFromGallery()
                    }
                }
                .show()
        }
    }

    /** 打开相册选择图片 */
    private fun pickFromGallery() {
        imagePickerLauncher.launch("image/*")
    }

    /** 拍照（暂不支持，引导用户用相册） */
    private fun takePhoto() {
        // 相机需要 CAMERA 权限 + FileProvider，暂不实现
        // 直接跳转到相册选择
        pickFromGallery()
    }

    // ==================== 数据观测 ====================

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatViewModel.messages.collect { messages ->
                        chatAdapter.submitList(messages)
                        if (messages.isNotEmpty()) {
                            binding.chatRecycler.post {
                                binding.chatRecycler.smoothScrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }

                launch {
                    chatViewModel.suggestions.collect { suggestions ->
                        renderSuggestions(suggestions)
                    }
                }

                launch {
                    chatViewModel.isLoading.collect { loading ->
                        if (loading) {
                            binding.etInput.isEnabled = false
                            binding.btnSend.isEnabled = false
                            binding.btnSend.setTextColor(0xFF555577.toInt())
                        } else {
                            binding.etInput.isEnabled = true
                            binding.btnSend.isEnabled = true
                            binding.btnSend.setTextColor(0xFFFF6B35.toInt())
                        }
                    }
                }
            }
        }
    }

    // ==================== 建议问题渲染 ====================

    private fun renderSuggestions(suggestions: List<SuggestedQuestion>) {
        val container = binding.suggestionContainer
        container.removeAllViews()

        if (chatViewModel.getMessageCount() > 15) {
            binding.suggestionBar.visibility = View.GONE
            return
        }
        binding.suggestionBar.visibility = View.VISIBLE

        for (suggestion in suggestions) {
            val chip = createSuggestionChip(suggestion)
            container.addView(chip)
        }
    }

    private fun createSuggestionChip(suggestion: SuggestedQuestion): TextView {
        return TextView(requireContext()).apply {
            text = "${suggestion.icon} ${suggestion.text}"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_chip_inactive)
            val padH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
            val padV = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            setOnClickListener { chatViewModel.sendMessage(suggestion.text) }
        }
    }

    // ==================== 发送消息 ====================

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return

        binding.etInput.setText("")
        chatViewModel.sendMessage(text)
    }
}
