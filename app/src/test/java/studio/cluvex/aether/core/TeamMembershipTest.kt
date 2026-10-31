package studio.cluvex.aether.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which files are a team's enrolment, and that forgetting one team touches nothing
 * else - not personal WARP, not a team whose name starts the same way.
 */
class TeamMembershipTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zt-membership").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(vararg names: String) = names.forEach { File(dir, it).writeText("x") }

    @Test
    fun `every name the engine derives for a team belongs to it`() {
        val p = TeamMembership.patternFor("acme")
        for (name in listOf(
            "aether-team-acme.toml",
            "aether-team-acme.toml.sealed",
            "aether-team-acme.toml.corrupt",
            "aether-team-acme.toml.corrupt.sealed",
            "aether-team-acme-lastconn.toml",
            "aether-team-acme-secondary.toml",
            "aether-team-acme-secondary-lastconn.toml.sealed",
        )) {
            assertTrue(name, p.matches(name))
        }
    }

    @Test
    fun `other teams and personal identities never match`() {
        val p = TeamMembership.patternFor("acme")
        for (name in listOf(
            "aether.toml",
            "aether-masque.toml",
            "aether-lastconn.toml",
            "aether-team-acme-eu.toml",
            "aether-team-acme-eu-lastconn.toml",
            "aether-team-acmex.toml",
            "aether-team-acme.toml.bak",
            "xaether-team-acme.toml",
        )) {
            assertFalse(name, p.matches(name))
        }
    }

    @Test
    fun `a regex character in the name is taken literally`() {
        assertFalse(TeamMembership.patternFor("a.c").matches("aether-team-abc.toml"))
        assertTrue(TeamMembership.patternFor("a.c").matches("aether-team-a.c.toml"))
    }

    @Test
    fun `enrolled means the identity itself, plain or sealed`() {
        assertFalse(TeamMembership.isEnrolled(dir, "acme"))
        touch("aether-team-acme-lastconn.toml", "aether-team-acme.toml.corrupt")
        assertFalse(TeamMembership.isEnrolled(dir, "acme"))
        touch("aether-team-acme.toml.sealed")
        assertTrue(TeamMembership.isEnrolled(dir, "acme"))
        assertFalse(TeamMembership.isEnrolled(dir, ""))
    }

    @Test
    fun `forgetting one team leaves everything else in place`() {
        touch(
            "aether.toml.sealed",
            "aether-masque.toml",
            "aether-team-acme.toml.sealed",
            "aether-team-acme-lastconn.toml",
            "aether-team-acme-secondary.toml.sealed",
            "aether-team-acme-secondary-lastconn.toml",
            "aether-team-acme.toml.corrupt",
            "aether-team-acme-eu.toml.sealed",
            "hev.yaml",
        )
        assertEquals(5, TeamMembership.forget(dir, "acme"))
        assertFalse(TeamMembership.isEnrolled(dir, "acme"))
        assertTrue(TeamMembership.isEnrolled(dir, "acme-eu"))
        assertEquals(
            setOf("aether.toml.sealed", "aether-masque.toml", "aether-team-acme-eu.toml.sealed", "hev.yaml"),
            dir.list()!!.toSet(),
        )
    }
}
