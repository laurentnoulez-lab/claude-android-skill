namespace Mao.Domain.Entities;

/// <summary>Utilisateur de l'application (table <c>AGENT</c> / gestion des utilisateurs).</summary>
public class Utilisateur
{
    public int Id { get; set; }
    public string Code { get; set; } = string.Empty;
    public string Nom { get; set; } = string.Empty;

    /// <summary>Rôle : « Administrateur » ou « Utilisateur ».</summary>
    public string Role { get; set; } = "Utilisateur";

    public bool Actif { get; set; } = true;
}

/// <summary>Entité administrative (table <c>ENTITE</c>), ex. direction / district.</summary>
public class EntiteAdmin
{
    public int Id { get; set; }
    public string Code { get; set; } = string.Empty;
    public string Nom { get; set; } = string.Empty;

    public List<AgentAdmin> Agents { get; set; } = new();
}

/// <summary>Agent administratif rattaché à une entité.</summary>
public class AgentAdmin
{
    public int Id { get; set; }
    public string Nom { get; set; } = string.Empty;
    public string? Prenom { get; set; }

    public int EntiteId { get; set; }
    public EntiteAdmin? Entite { get; set; }
}

/// <summary>
/// Paramètre applicatif clé/valeur. Remplace les fichiers .ini éparpillés de
/// MAO V8 (sections [PARAM], [OPTION_RECAP]…) par une configuration centralisée.
/// </summary>
public class Parametre
{
    public string Cle { get; set; } = string.Empty;
    public string? Valeur { get; set; }
    public string? Description { get; set; }
}
