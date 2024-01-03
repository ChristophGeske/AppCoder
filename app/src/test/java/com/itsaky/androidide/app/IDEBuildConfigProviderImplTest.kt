/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.app

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.BuildConfig.ABI_ARM64_V8A
import com.itsaky.androidide.BuildConfig.ABI_ARMEABI_V7A
import com.itsaky.androidide.BuildConfig.ABI_X86_64
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.app.configuration.IDEBuildConfigProviderImpl
import org.junit.Test

/**
 * @author Akash Yadav
 */
class IDEBuildConfigProviderImplTest {

  class TestBuildConfigProvider(override val cpuAbiName: String,
    override val supportedAbis: Array<String>) : IDEBuildConfigProviderImpl()

  @Test
  fun `test aarch64 build on aarch64-only device`() {
    TestBuildConfigProvider(ABI_ARM64_V8A, arrayOf(ABI_ARM64_V8A)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_ARM64_V8A)
      assertThat(cpuArch).isEqualTo(CpuArch.AARCH64)
      assertThat(deviceArch).isEqualTo(CpuArch.AARCH64)

      assertThat(isArm64v8aBuild()).isTrue()
      assertThat(isX86_64Build()).isFalse()
      assertThat(isArmeabiv7aBuild()).isFalse()

      assertThat(isArm64v8aDevice()).isTrue()
      assertThat(isX86_64Device()).isFalse()
      assertThat(isArmeabiv7aDevice()).isFalse()

      assertThat(supportsBuildFlavor()).isTrue()
      assertThat(cpuArch).isEqualTo(deviceArch)
    }
  }

  @Test
  fun `test arm build on arm-only device`() {
    TestBuildConfigProvider(ABI_ARMEABI_V7A, arrayOf(ABI_ARMEABI_V7A)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_ARMEABI_V7A)
      assertThat(cpuArch).isEqualTo(CpuArch.ARM)
      assertThat(deviceArch).isEqualTo(CpuArch.ARM)

      assertThat(isArm64v8aBuild()).isFalse()
      assertThat(isX86_64Build()).isFalse()
      assertThat(isArmeabiv7aBuild()).isTrue()

      assertThat(isArm64v8aDevice()).isFalse()
      assertThat(isX86_64Device()).isFalse()
      assertThat(isArmeabiv7aDevice()).isTrue()

      assertThat(supportsBuildFlavor()).isTrue()
      assertThat(cpuArch).isEqualTo(deviceArch)
    }
  }

  @Test
  fun `test x86_64 build on x86_64-only device`() {
    TestBuildConfigProvider(ABI_X86_64, arrayOf(ABI_X86_64)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_X86_64)
      assertThat(cpuArch).isEqualTo(CpuArch.X86_64)
      assertThat(deviceArch).isEqualTo(CpuArch.X86_64)

      assertThat(isArm64v8aBuild()).isFalse()
      assertThat(isX86_64Build()).isTrue()
      assertThat(isArmeabiv7aBuild()).isFalse()

      assertThat(isArm64v8aDevice()).isFalse()
      assertThat(isX86_64Device()).isTrue()
      assertThat(isArmeabiv7aDevice()).isFalse()

      assertThat(supportsBuildFlavor()).isTrue()
      assertThat(cpuArch).isEqualTo(deviceArch)
    }
  }

  @Test
  fun `test arm build on (aarch64,arm) device`() {
    TestBuildConfigProvider(ABI_ARMEABI_V7A, arrayOf(ABI_ARM64_V8A, ABI_ARMEABI_V7A)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_ARMEABI_V7A)
      assertThat(cpuArch).isEqualTo(CpuArch.ARM)
      assertThat(deviceArch).isEqualTo(CpuArch.AARCH64)

      assertThat(isArm64v8aBuild()).isFalse()
      assertThat(isX86_64Build()).isFalse()
      assertThat(isArmeabiv7aBuild()).isTrue()

      assertThat(isArm64v8aDevice()).isTrue()
      assertThat(isX86_64Device()).isFalse()
      assertThat(isArmeabiv7aDevice()).isFalse()

      assertThat(supportsBuildFlavor()).isTrue()
      assertThat(cpuArch).isNotEqualTo(deviceArch)
    }
  }

  @Test
  fun `test arm build on (x86_64,arm) device`() {
    TestBuildConfigProvider(ABI_ARMEABI_V7A, arrayOf(ABI_X86_64, ABI_ARMEABI_V7A)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_ARMEABI_V7A)
      assertThat(cpuArch).isEqualTo(CpuArch.ARM)
      assertThat(deviceArch).isEqualTo(CpuArch.X86_64)

      assertThat(isArm64v8aBuild()).isFalse()
      assertThat(isX86_64Build()).isFalse()
      assertThat(isArmeabiv7aBuild()).isTrue()

      assertThat(isArm64v8aDevice()).isFalse()
      assertThat(isX86_64Device()).isTrue()
      assertThat(isArmeabiv7aDevice()).isFalse()

      assertThat(supportsBuildFlavor()).isTrue()
      assertThat(cpuArch).isNotEqualTo(deviceArch)
    }
  }

  @Test
  fun `test aarch64 build on arm-only device`() {
    TestBuildConfigProvider(ABI_ARM64_V8A, arrayOf(ABI_ARMEABI_V7A)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_ARM64_V8A)
      assertThat(cpuArch).isEqualTo(CpuArch.AARCH64)
      assertThat(deviceArch).isEqualTo(CpuArch.ARM)

      assertThat(isArm64v8aBuild()).isTrue()
      assertThat(isX86_64Build()).isFalse()
      assertThat(isArmeabiv7aBuild()).isFalse()

      assertThat(isArm64v8aDevice()).isFalse()
      assertThat(isX86_64Device()).isFalse()
      assertThat(isArmeabiv7aDevice()).isTrue()

      assertThat(supportsBuildFlavor()).isFalse()
      assertThat(cpuArch).isNotEqualTo(deviceArch)
    }
  }

  @Test
  fun `test aarch64 build on x86_64-only device`() {
    TestBuildConfigProvider(ABI_ARM64_V8A, arrayOf(ABI_X86_64)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_ARM64_V8A)
      assertThat(cpuArch).isEqualTo(CpuArch.AARCH64)
      assertThat(deviceArch).isEqualTo(CpuArch.X86_64)

      assertThat(isArm64v8aBuild()).isTrue()
      assertThat(isX86_64Build()).isFalse()
      assertThat(isArmeabiv7aBuild()).isFalse()

      assertThat(isArm64v8aDevice()).isFalse()
      assertThat(isX86_64Device()).isTrue()
      assertThat(isArmeabiv7aDevice()).isFalse()

      assertThat(supportsBuildFlavor()).isFalse()
      assertThat(cpuArch).isNotEqualTo(deviceArch)
    }
  }

  @Test
  fun `test aarch64 build on (x86_64,aarch64) device`() {
    TestBuildConfigProvider(ABI_ARM64_V8A, arrayOf(ABI_X86_64, ABI_ARM64_V8A)).apply {
      assertThat(cpuAbiName).isEqualTo(ABI_ARM64_V8A)
      assertThat(cpuArch).isEqualTo(CpuArch.AARCH64)
      assertThat(deviceArch).isEqualTo(CpuArch.X86_64)

      assertThat(isArm64v8aBuild()).isTrue()
      assertThat(isX86_64Build()).isFalse()
      assertThat(isArmeabiv7aBuild()).isFalse()

      assertThat(isArm64v8aDevice()).isFalse()
      assertThat(isX86_64Device()).isTrue()
      assertThat(isArmeabiv7aDevice()).isFalse()

      assertThat(supportsBuildFlavor()).isTrue()
      assertThat(cpuArch).isNotEqualTo(deviceArch)
    }
  }
}