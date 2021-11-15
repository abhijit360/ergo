package org.ergoplatform.nodeView.wallet

import org.ergoplatform.utils.ErgoPropertyTest
import org.ergoplatform.wallet.interpreter.ErgoProvingInterpreter

class WalletVarsSpec extends ErgoPropertyTest {

  property(".withProver init") {
    val prover = ErgoProvingInterpreter(defaultRootSecret, parameters)
    val walletVars = WalletVars(None, Seq.empty, None)
    val wp = walletVars.withProver(prover)

    wp.trackedPubKeys.length shouldBe 1
  }

}
