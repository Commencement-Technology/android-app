package net.ivpn.core.v2.tfa

/*
 IVPN Android app
 https://github.com/ivpn/android-app

 Created by Oleksandr Mykhailenko.
 Copyright (c) 2023 IVPN Limited.

 This file is part of the IVPN Android app.

 The IVPN Android app is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as published by the Free
 Software Foundation, either version 3 of the License, or (at your option) any later version.

 The IVPN Android app is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 details.

 You should have received a copy of the GNU General Public License
 along with the IVPN Android app. If not, see <https://www.gnu.org/licenses/>.
*/

import android.content.Context
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import net.ivpn.core.IVPNApplication
import net.ivpn.core.R
import net.ivpn.core.common.billing.addfunds.Plan
import net.ivpn.core.common.extension.findNavControllerSafely
import net.ivpn.core.common.extension.navigate
import net.ivpn.core.common.extension.setNavigationResultBoolean
import net.ivpn.core.databinding.FragmentTfaBinding
import net.ivpn.core.rest.data.session.SessionErrorResponse
import net.ivpn.core.v2.connect.createSession.CreateSessionFragment
import net.ivpn.core.v2.dialog.DialogBuilder
import net.ivpn.core.v2.dialog.Dialogs
import net.ivpn.core.v2.login.LoginNavigator
import net.ivpn.core.v2.MainActivity
import net.ivpn.core.v2.login.LoginViewModel
import net.ivpn.core.v2.signup.SignUpController
import org.slf4j.LoggerFactory
import javax.inject.Inject

class TFAFragment : Fragment(), LoginNavigator {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(TFAFragment::class.java)
    }

    lateinit var binding: FragmentTfaBinding

    @Inject
    lateinit var viewModel: LoginViewModel

    var signUp: SignUpController = IVPNApplication.signUpController

//    @Inject
//    lateinit var signUp: SignUpViewModel

    private var originalMode: Int? = null
    private var createSessionFragment: CreateSessionFragment? = null


    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_tfa, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        IVPNApplication.appComponent.provideActivityComponent().create().inject(this)
        initViews()
        initToolbar()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        LOGGER.info("onAttach original mode = $originalMode")
    }

    override fun onDetach() {
        super.onDetach()
        LOGGER.info("onDetach original mode = $originalMode")
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStart() {
        super.onStart()
        setNavigationResultBoolean(false, "session_limit_dialogue")
        activity?.let {
            if (it is MainActivity) {
                it.setAdjustResizeMode()
                it.setContentSecure(false)
            }
        }
        viewModel.navigator = this
    }

    private fun initViews() {
        binding.contentLayout.viewmodel = viewModel

        binding.contentLayout.editText.onFocusChangeListener = viewModel.tfaFocusListener
        binding.contentLayout.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.submit2FAToken()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        binding.contentLayout.submitButton.setOnClickListener {
            viewModel.submit2FAToken()
        }
    }

    private fun initToolbar() {
        val navController = findNavController()
        val appBarConfiguration = AppBarConfiguration(navController.graph)

        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
    }

    override fun onLogin() {
        LOGGER.info("onLogin")
        val action = TFAFragmentDirections.actionTFAFragmentToSyncFragment()
        navigate(action)
    }

    override fun onLoginWithBlankAccount() {
        if (viewModel.isAccountNewStyle()) {
            signUp.signUpWith(findNavControllerSafely(), viewModel.username.get())
        } else {
            onLogin()
        }
    }

    override fun onLoginWithInactiveAccount() {
        if (viewModel.isAccountNewStyle()) {
            signUp.signUpWithInactiveAccount(findNavControllerSafely(),
                    Plan.getPlanByProductName(viewModel.getAccountType()),
                    viewModel.isAccountNewStyle())
        } else {
            onLogin()
        }
    }

    override fun onInvalidAccount() {
        //Nothing to do
    }

    override fun openSessionLimitReachedDialogue(error: SessionErrorResponse) {
        setNavigationResultBoolean(true, "session_limit_dialogue")
        findNavController().popBackStack()
    }

    override fun openCaptcha() {
        //This callback should never be fired.
    }

    override fun openErrorDialogue(dialog: Dialogs) {
        DialogBuilder.createNotificationDialog(context, dialog)
    }

    override fun openCustomErrorDialogue(title: String?, message: String?) {
        DialogBuilder.createFullCustomNotificationDialog(context, title, message)
    }

    override fun openTFAScreen() {
        //This callback should never be fired.
    }
}