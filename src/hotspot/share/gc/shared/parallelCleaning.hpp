/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_GC_SHARED_PARALLELCLEANING_HPP
#define SHARE_GC_SHARED_PARALLELCLEANING_HPP

#include "classfile/classLoaderDataGraph.hpp"
#include "code/codeCache.hpp"
#include "gc/shared/oopStorageParState.hpp"
#include "gc/shared/workerThread.hpp"

class CodeCacheUnloadingTask {

  const bool                _unloading_occurred;
  const uint                _num_workers;

  // Variables used to claim nmethods.
  nmethod* _first_nmethod;
  nmethod* volatile _claimed_nmethod;

public:
  CodeCacheUnloadingTask(uint num_workers, bool unloading_occurred);
  ~CodeCacheUnloadingTask();

private:
  static const int MaxClaimNmethods = 16;
  void claim_nmethods(nmethod** claimed_nmethods, int *num_claimed_nmethods);

public:
  // Cleaning and unloading of nmethods.
  void work(uint worker_id);
};

// Cleans out the Klass tree from stale data.
class KlassCleaningTask : public StackObj {
  volatile bool _clean_klass_tree_claimed;
  ClassLoaderDataGraphKlassIteratorAtomic _klass_iterator;

  bool claim_clean_klass_tree_task();
  InstanceKlass* claim_next_klass();

public:
  KlassCleaningTask();

  void work();
};

#endif // SHARE_GC_SHARED_PARALLELCLEANING_HPP
