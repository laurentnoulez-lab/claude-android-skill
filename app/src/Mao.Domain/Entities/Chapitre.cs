namespace Mao.Domain.Entities;

/// <summary>Regroupement thématique au sein d'une division (table <c>CHAPITRE</c>).</summary>
public class Chapitre
{
    public int Id { get; set; }
    public int DivisionId { get; set; }
    public Division? Division { get; set; }

    public int Numero { get; set; }
    public string Intitule { get; set; } = string.Empty;
    public DateTime DerniereMaj { get; set; } = DateTime.Now;

    public List<Poste> Postes { get; set; } = new();
}
