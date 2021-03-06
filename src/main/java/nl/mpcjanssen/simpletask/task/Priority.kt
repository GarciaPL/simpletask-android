/**
 * This file is part of Todo.txt Touch, an Android app for managing your todo.txt file (http://todotxt.com).

 * Copyright (c) 2009-2012 Todo.txt contributors (http://todotxt.com)

 * LICENSE:

 * Todo.txt Touch is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.

 * Todo.txt Touch is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.

 * You should have received a copy of the GNU General Public License along with Todo.txt Touch.  If not, see
 * //www.gnu.org/licenses/>.

 * @author Todo.txt contributors @yahoogroups.com>
 * *
 * @license http://www.gnu.org/licenses/gpl.html
 * *
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 */
package nl.mpcjanssen.simpletask.task

import java.util.ArrayList
import java.util.Locale

enum class Priority private constructor(val code: String, private val listFormat: String, private val detailFormat: String,
                                        private val fileFormat: String) {
    NONE("-", "   ", "", ""), A("A", "A", "A", "(A)"), B("B", "B", "B", "(B)"), C("C", "C", "C", "(C)"), D("D", "D", "D", "(D)"), E("E", "E", "E", "(E)"), F("F", "F", "F", "(F)"), G("G", "G", "G", "(G)"), H("H", "H", "H", "(H)"), I("I", "I", "I", "(I)"), J("J", "J", "J", "(J)"), K("K", "K", "K", "(K)"), L("L", "L", "L", "(L)"), M("M", "M", "M", "(M)"), N("N", "N", "N", "(N)"), O("O", "O", "O", "(O)"), P("P", "P", "P", "(P)"), Q("Q", "Q", "Q", "(Q)"), R("R", "R", "R", "(R)"), S("S", "S", "S", "(S)"), T("T", "T", "T", "(T)"), U("U", "U", "U", "(U)"), V("V", "V", "V", "(V)"), W("W", "W", "W", "(W)"), X("X", "X", "X", "(X)"), Y("Y", "Y", "Y", "(Y)"), Z("Z", "Z", "Z", "(Z)");

    fun inListFormat(): String {
        return listFormat
    }

    fun inDetailFormat(): String {
        return detailFormat
    }

    fun inFileFormat(): String {
        return fileFormat
    }

    companion object {

        private fun reverseValues(): Array<Priority> {
            val values = Priority.values()
            return values.reversed().toTypedArray()
        }

        fun range(p1: Priority, p2: Priority): List<Priority> {
            val priorities = ArrayList<Priority>()
            var add = false

            for (p in if (p1.ordinal < p2.ordinal)
                Priority.values()
            else
                Priority.reverseValues()) {
                if (p == p1) {
                    add = true
                }
                if (add) {
                    priorities.add(p)
                }
                if (p == p2) {
                    break
                }
            }
            return priorities
        }

        fun rangeInCode(p1: Priority, p2: Priority): List<String> {
            val priorities = Priority.range(p1, p2)
            val result = ArrayList<String>(priorities.size)
            for (p in priorities) {
                result.add(p.code)
            }
            return result
        }

        fun inCode(priorities: List<Priority>): ArrayList<String> {
            val strings = ArrayList<String>()
            for (p in priorities) {
                strings.add(p.code)
            }
            return strings
        }

        fun toPriority(codes: List<String>?): ArrayList<Priority> {
            val priorities = ArrayList<Priority>()
            if (codes == null) {
                return ArrayList()
            }
            for (code in codes) {
                priorities.add(Priority.toPriority(code))
            }
            return priorities
        }

        fun toPriority(s: String?): Priority {
            if (s == null) {
                return NONE
            }

            for (p in Priority.values()) {
                if (p.code == s.toUpperCase(Locale.US)) {
                    return p
                }
            }
            return NONE
        }
    }

}
